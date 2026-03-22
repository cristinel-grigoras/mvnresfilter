# Integration Testing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add 14 integration tests in two tiers — 6 platform tests via MavenImportingTestCase (separate Gradle task) and 8 component tests covering error handling, profile combos, and multi-module wiring.

**Architecture:** Platform tests extend `MavenImportingTestCase` which provides a real IntelliJ project with Maven integration. They call `MavenResourceOverlayTask.execute()` directly against the imported Maven project. Component tests wire `MavenModelReader` + `ResourceProcessor` together with new fixture projects. A shared `TestUtils` object eliminates duplication across existing and new test files.

**Tech Stack:** Kotlin, JUnit 4, IntelliJ Platform test framework, `TestFrameworkType.Plugin.Maven` (`com.jetbrains.intellij.maven:maven-test-framework`), Gradle Kotlin DSL

**Spec:** `docs/superpowers/specs/2026-03-22-integration-testing-design.md`

---

## File Structure

### New files

| File | Responsibility |
|------|---------------|
| `src/test/kotlin/ro/gs1s/mvnresfilter/integration/TestUtils.kt` | Shared test helpers: `copyTree`, `readText`, `compareTree`, `resolveRelativePaths`, `buildCacheInputs` |
| `src/test/kotlin/ro/gs1s/mvnresfilter/integration/ErrorHandlingIntegrationTest.kt` | 3 tests: missing pom, malformed pom, empty profile |
| `src/test/kotlin/ro/gs1s/mvnresfilter/integration/ProfileCombinationsIntegrationTest.kt` | 2 tests: multi-profile webResources combined, overlapping files |
| `src/test/kotlin/ro/gs1s/mvnresfilter/integration/MultiModuleIntegrationTest.kt` | 3 tests: parent property inheritance, child overrides, cross-module profiles |
| `src/test/resources/projects/malformed-pom/pom.xml` | Truncated/invalid pom.xml fixture |
| `src/test/resources/projects/multi-profile/pom.xml` | Pom with 2 profiles having webResources + overlapping files |
| `src/test/resources/projects/multi-profile/profiles/alpha/WEB-INF/config.json` | Profile alpha overlay file |
| `src/test/resources/projects/multi-profile/profiles/beta/WEB-INF/config.json` | Profile beta overlay file (overlaps alpha) |
| `src/test/resources/projects/multi-profile/profiles/beta/WEB-INF/extra.json` | Profile beta extra file |
| `src/test/resources/projects/multi-profile/src/main/webapp/WEB-INF/web.xml` | Webapp descriptor with `${...}` placeholders |
| `src/test/kotlin/ro/gs1s/mvnresfilter/platform/MavenOverlayPlatformTest.kt` | 6 platform tests: WAR dev/prod, profile switch, JAR, no maven, defaults |

### Modified files

| File | Change |
|------|--------|
| `build.gradle.kts` | Add integrationTest source set/task + `TestFrameworkType.Plugin.Maven` dependency |
| `src/test/kotlin/ro/gs1s/mvnresfilter/integration/WarOverlayIntegrationTest.kt` | Replace duplicated companion helpers with `TestUtils` |
| `src/test/kotlin/ro/gs1s/mvnresfilter/integration/JarFilteringIntegrationTest.kt` | Replace duplicated companion helpers with `TestUtils` |
| `src/test/kotlin/ro/gs1s/mvnresfilter/integration/IncrementalBuildTest.kt` | Replace duplicated companion helpers with `TestUtils` |

---

## Task 1: Extract TestUtils from existing integration tests

**Files:**
- Create: `src/test/kotlin/ro/gs1s/mvnresfilter/integration/TestUtils.kt`
- Modify: `src/test/kotlin/ro/gs1s/mvnresfilter/integration/WarOverlayIntegrationTest.kt`
- Modify: `src/test/kotlin/ro/gs1s/mvnresfilter/integration/JarFilteringIntegrationTest.kt`
- Modify: `src/test/kotlin/ro/gs1s/mvnresfilter/integration/IncrementalBuildTest.kt`

- [ ] **Step 1: Create TestUtils.kt with shared helpers**

Extract the duplicated `copyTree`, `readText`, `compareTree`, `resolveRelativePaths`, and `buildCacheInputs` methods from the three existing integration test companion objects into a single `TestUtils` object.

```kotlin
package ro.gs1s.mvnresfilter.integration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import ro.gs1s.mvnresfilter.OverlayCacheInputs
import ro.gs1s.mvnresfilter.OverlayConfig
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

object TestUtils {
    val PROJECTS_BASE: Path = Path.of("src/test/resources/projects")
    val EXPECTED_BASE: Path = Path.of("src/test/resources/expected")

    fun copyTree(source: Path, target: Path) {
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

    fun readText(path: Path): String =
        Files.readString(path, StandardCharsets.UTF_8).replace("\r\n", "\n")

    fun compareTree(expectedDir: Path, actualDir: Path) {
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

    fun resolveRelativePaths(config: OverlayConfig): OverlayConfig {
        val basedir = config.projectBasedir
        val resolvedWebResources = config.webResources.map { wr ->
            val dir = Path.of(wr.directory)
            val absDir = if (dir.isAbsolute) dir else basedir.resolve(dir)
            wr.copy(directory = absDir.toString())
        }
        val resolvedResources = config.resources.map { r ->
            val dir = Path.of(r.directory)
            val absDir = if (dir.isAbsolute) dir else basedir.resolve(dir)
            r.copy(directory = absDir.toString())
        }
        return config.copy(
            webResources = resolvedWebResources,
            resources = resolvedResources
        )
    }

    fun buildCacheInputs(config: OverlayConfig): OverlayCacheInputs {
        val sourceFiles = mutableMapOf<String, Long>()
        for (resource in config.resources) {
            val dir = Path.of(resource.directory)
            if (Files.exists(dir) && Files.isDirectory(dir)) {
                Files.walk(dir).use { stream ->
                    stream.filter { Files.isRegularFile(it) }.forEach { file ->
                        sourceFiles[file.toString()] = Files.getLastModifiedTime(file).toMillis()
                    }
                }
            }
        }
        for (webResource in config.webResources) {
            val dir = Path.of(webResource.directory)
            if (Files.exists(dir) && Files.isDirectory(dir)) {
                Files.walk(dir).use { stream ->
                    stream.filter { Files.isRegularFile(it) }.forEach { file ->
                        sourceFiles[file.toString()] = Files.getLastModifiedTime(file).toMillis()
                    }
                }
            }
        }
        return OverlayCacheInputs(
            profiles = config.activeProfiles,
            properties = config.mergedProperties,
            sourceFiles = sourceFiles
        )
    }
}
```

- [ ] **Step 2: Refactor WarOverlayIntegrationTest to use TestUtils**

Remove the entire `companion object` block and replace all calls: `copyTree(...)` → `TestUtils.copyTree(...)`, `readText(...)` → `TestUtils.readText(...)`, `compareTree(...)` → `TestUtils.compareTree(...)`, `resolveRelativePaths(...)` → `TestUtils.resolveRelativePaths(...)`. Update `PROJECTS_BASE`/`EXPECTED_BASE` references to use `TestUtils.PROJECTS_BASE`/`TestUtils.EXPECTED_BASE`.

- [ ] **Step 3: Refactor JarFilteringIntegrationTest to use TestUtils**

Same refactor as step 2 — remove companion object, use `TestUtils.*` calls.

- [ ] **Step 4: Refactor IncrementalBuildTest to use TestUtils**

Same refactor — remove companion object, use `TestUtils.*` calls. This file also has `buildCacheInputs()` which moves to `TestUtils.buildCacheInputs()`.

- [ ] **Step 5: Run existing tests to verify no regressions**

Run: `./gradlew test`
Expected: All 39 existing tests pass. Zero failures.

- [ ] **Step 6: Commit**

```bash
git add src/test/kotlin/ro/gs1s/mvnresfilter/integration/TestUtils.kt \
        src/test/kotlin/ro/gs1s/mvnresfilter/integration/WarOverlayIntegrationTest.kt \
        src/test/kotlin/ro/gs1s/mvnresfilter/integration/JarFilteringIntegrationTest.kt \
        src/test/kotlin/ro/gs1s/mvnresfilter/integration/IncrementalBuildTest.kt
git commit -m "Extract shared TestUtils from integration tests"
```

---

## Task 2: Create test fixtures for new component tests

**Files:**
- Create: `src/test/resources/projects/malformed-pom/pom.xml`
- Create: `src/test/resources/projects/multi-profile/pom.xml`
- Create: `src/test/resources/projects/multi-profile/profiles/alpha/WEB-INF/config.json`
- Create: `src/test/resources/projects/multi-profile/profiles/beta/WEB-INF/config.json`
- Create: `src/test/resources/projects/multi-profile/profiles/beta/WEB-INF/extra.json`
- Create: `src/test/resources/projects/multi-profile/src/main/webapp/WEB-INF/web.xml`

- [ ] **Step 1: Create malformed-pom fixture**

`src/test/resources/projects/malformed-pom/pom.xml` — truncated XML that will cause a parse error:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
    <groupId>com.example</groupId>
    <!-- deliberately truncated — no closing tags -->
```

- [ ] **Step 2: Create multi-profile fixture pom.xml**

`src/test/resources/projects/multi-profile/pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <groupId>com.example</groupId>
    <artifactId>multi-profile</artifactId>
    <version>1.0.0</version>
    <packaging>war</packaging>

    <properties>
        <app.name>BaseApp</app.name>
    </properties>

    <profiles>
        <profile>
            <id>alpha</id>
            <properties>
                <app.name>AlphaApp</app.name>
                <alpha.prop>alpha-value</alpha.prop>
            </properties>
            <build>
                <plugins>
                    <plugin>
                        <groupId>org.apache.maven.plugins</groupId>
                        <artifactId>maven-war-plugin</artifactId>
                        <configuration>
                            <filteringDeploymentDescriptors>true</filteringDeploymentDescriptors>
                            <webResources>
                                <resource>
                                    <directory>profiles/alpha</directory>
                                </resource>
                            </webResources>
                        </configuration>
                    </plugin>
                </plugins>
            </build>
        </profile>
        <profile>
            <id>beta</id>
            <properties>
                <app.name>BetaApp</app.name>
                <beta.prop>beta-value</beta.prop>
            </properties>
            <build>
                <plugins>
                    <plugin>
                        <groupId>org.apache.maven.plugins</groupId>
                        <artifactId>maven-war-plugin</artifactId>
                        <configuration>
                            <filteringDeploymentDescriptors>true</filteringDeploymentDescriptors>
                            <webResources>
                                <resource>
                                    <directory>profiles/beta</directory>
                                </resource>
                            </webResources>
                        </configuration>
                    </plugin>
                </plugins>
            </build>
        </profile>
        <profile>
            <id>empty</id>
            <properties>
                <app.name>EmptyApp</app.name>
            </properties>
            <!-- No war-plugin config — no webResources -->
        </profile>
    </profiles>
</project>
```

- [ ] **Step 3: Create multi-profile overlay files**

`profiles/alpha/WEB-INF/config.json`:
```json
{"profile": "alpha", "setting": "alpha-config"}
```

`profiles/beta/WEB-INF/config.json` (overlaps alpha — same path):
```json
{"profile": "beta", "setting": "beta-config"}
```

`profiles/beta/WEB-INF/extra.json` (unique to beta):
```json
{"extra": "beta-only"}
```

- [ ] **Step 4: Create multi-profile webapp descriptor**

`src/main/webapp/WEB-INF/web.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<web-app>
    <display-name>${app.name}</display-name>
</web-app>
```

- [ ] **Step 5: Commit**

```bash
git add src/test/resources/projects/malformed-pom/ \
        src/test/resources/projects/multi-profile/
git commit -m "Add test fixtures for multi-profile and malformed-pom scenarios"
```

---

## Task 3: ErrorHandlingIntegrationTest (3 tests)

**Files:**
- Create: `src/test/kotlin/ro/gs1s/mvnresfilter/integration/ErrorHandlingIntegrationTest.kt`

- [ ] **Step 1: Write ErrorHandlingIntegrationTest**

```kotlin
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
```

- [ ] **Step 2: Run the tests**

Run: `./gradlew test --tests "ro.gs1s.mvnresfilter.integration.ErrorHandlingIntegrationTest"`
Expected: All 3 tests pass.

- [ ] **Step 3: Commit**

```bash
git add src/test/kotlin/ro/gs1s/mvnresfilter/integration/ErrorHandlingIntegrationTest.kt
git commit -m "Add error handling integration tests"
```

---

## Task 4: ProfileCombinationsIntegrationTest (2 tests)

**Files:**
- Create: `src/test/kotlin/ro/gs1s/mvnresfilter/integration/ProfileCombinationsIntegrationTest.kt`

- [ ] **Step 1: Write ProfileCombinationsIntegrationTest**

```kotlin
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
        val multiProfilePom = TestUtils.PROJECTS_BASE.resolve("multi-profile/pom.xml")
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
```

- [ ] **Step 2: Run the tests**

Run: `./gradlew test --tests "ro.gs1s.mvnresfilter.integration.ProfileCombinationsIntegrationTest"`
Expected: All 2 tests pass.

- [ ] **Step 3: Commit**

```bash
git add src/test/kotlin/ro/gs1s/mvnresfilter/integration/ProfileCombinationsIntegrationTest.kt
git commit -m "Add profile combinations integration tests"
```

---

## Task 5: MultiModuleIntegrationTest (3 tests)

**Files:**
- Create: `src/test/kotlin/ro/gs1s/mvnresfilter/integration/MultiModuleIntegrationTest.kt`

Uses existing `multi-module` fixture. Tests verify property inheritance and override behavior through to actual file output.

- [ ] **Step 1: Write MultiModuleIntegrationTest**

```kotlin
package ro.gs1s.mvnresfilter.integration

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ro.gs1s.mvnresfilter.ArtifactType
import ro.gs1s.mvnresfilter.MavenModelReader
import ro.gs1s.mvnresfilter.ResourceProcessor
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class MultiModuleIntegrationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val reader = MavenModelReader()

    @Test
    fun testDeepInheritance_ParentProperties() {
        // Parent defines project-level properties, child dev profile overrides some
        // Verify the override propagates through to actual file content
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

        // Parent has jbossweb.context=myapp, dev profile overrides to myapp-dev
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
        // Parent defines hibernate.show_sql=false, dev profile sets it to true
        // Verify profile wins over parent project-level property
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

        // Parent project-level: hibernate.show_sql=false
        // Dev profile (in parent): hibernate.show_sql=true — profile wins
        assertEquals("true", config.mergedProperties["hibernate.show_sql"])

        val processor = ResourceProcessor(config)
        processor.processResources()

        val persistence = TestUtils.readText(artifactOutput.resolve("META-INF/persistence.xml"))
        assertFalse("persistence.xml should not have unresolved placeholder",
            persistence.contains("\${hibernate.show_sql}"))
        assertTrue("persistence.xml should have show_sql=true from dev profile",
            persistence.contains("hibernate.show_sql\">true"))
    }

    @Test
    fun testParentProfile_ChildProfile_BothActive() {
        // Activate dev profile — parent and child both have it
        // Properties from both should merge (parent's profile provides base, child inherits)
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

        // Both parent and child contribute dev profile properties
        // Parent dev: hibernate.show_sql=true, jbossweb.context=myapp-dev
        assertNotNull("Should have jbossweb.context from parent dev profile",
            config.mergedProperties["jbossweb.context"])
        assertEquals("myapp-dev", config.mergedProperties["jbossweb.context"])

        val processor = ResourceProcessor(config)
        processor.processWebResources()

        // Verify overlay files from child's profile dirs were copied
        assertTrue("keycloak.json should be overlaid",
            Files.exists(artifactOutput.resolve("WEB-INF/keycloak.json")))
    }
}
```

- [ ] **Step 2: Run the tests**

Run: `./gradlew test --tests "ro.gs1s.mvnresfilter.integration.MultiModuleIntegrationTest"`
Expected: All 3 tests pass.

- [ ] **Step 3: Commit**

```bash
git add src/test/kotlin/ro/gs1s/mvnresfilter/integration/MultiModuleIntegrationTest.kt
git commit -m "Add multi-module integration tests"
```

---

## Task 6: Run full test suite before platform tests

- [ ] **Step 1: Run all tests**

Run: `./gradlew test`
Expected: 47 tests pass (39 existing + 8 new component tests). Zero failures.

- [ ] **Step 2: Commit if any fixes were needed**

Only if step 1 required fixes. Otherwise skip.

---

## Task 7: Gradle setup for integrationTest source set

**Files:**
- Modify: `build.gradle.kts`

This is the critical step that may need iteration. Try the separate source set first; fall back to test filtering if it doesn't work.

- [ ] **Step 1: Add integrationTest source set and task to build.gradle.kts**

Add after the existing `kotlin { ... }` block:

```kotlin
sourceSets {
    create("integrationTest") {
        compileClasspath += sourceSets.main.get().output + sourceSets.test.get().output
        runtimeClasspath += sourceSets.main.get().output + sourceSets.test.get().output
    }
}

val integrationTestImplementation by configurations.getting {
    extendsFrom(configurations.testImplementation.get())
}
val integrationTestRuntimeOnly by configurations.getting {
    extendsFrom(configurations.testRuntimeOnly.get())
}

tasks.register<Test>("integrationTest") {
    testClassesDirs = sourceSets["integrationTest"].output.classesDirs
    classpath = sourceSets["integrationTest"].runtimeClasspath
}
```

And in the `dependencies { intellijPlatform { ... } }` block, add:

```kotlin
testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Plugin.Maven,
    configurationName = "integrationTestImplementation")
```

- [ ] **Step 2: Create a trivial smoke test to verify the source set compiles**

Create `src/integrationTest/kotlin/ro/gs1s/mvnresfilter/platform/SmokeTest.kt`:

```kotlin
package ro.gs1s.mvnresfilter.platform

import com.intellij.maven.testFramework.MavenImportingTestCase

class SmokeTest : MavenImportingTestCase() {
    fun testSmoke() {
        // Just verify MavenImportingTestCase can be instantiated and the test runs
        assertNotNull(project)
    }
}
```

- [ ] **Step 3: Compile and run the smoke test**

Run: `./gradlew integrationTest`

**If it succeeds:** The source set is properly wired. Delete `SmokeTest.kt` (`rm src/integrationTest/kotlin/ro/gs1s/mvnresfilter/platform/SmokeTest.kt`) and proceed to Task 8.

**If it fails with classpath errors:** Fall back to the test filtering approach. Move the platform tests into `src/test/kotlin/ro/gs1s/mvnresfilter/platform/` instead and configure:

```kotlin
// Replace the source set approach with test filtering
tasks.test { exclude("**/platform/**") }
tasks.register<Test>("integrationTest") {
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    include("**/platform/**")
}
```

And add the Maven test framework to the regular test configuration:

```kotlin
testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Plugin.Maven)
```

Re-run `./gradlew integrationTest` to verify.

- [ ] **Step 4: Commit**

```bash
git add build.gradle.kts
git commit -m "Add integrationTest Gradle task with Maven test framework"
```

---

## Task 8: MavenOverlayPlatformTest (6 tests)

**Files:**
- Create: `src/integrationTest/kotlin/ro/gs1s/mvnresfilter/platform/MavenOverlayPlatformTest.kt` (or `src/test/kotlin/.../platform/` if fallback was used in Task 7)

- [ ] **Step 1: Write MavenOverlayPlatformTest**

```kotlin
package ro.gs1s.mvnresfilter.platform

import com.intellij.maven.testFramework.MavenImportingTestCase
import junit.framework.TestCase
import ro.gs1s.mvnresfilter.ArtifactType
import ro.gs1s.mvnresfilter.MavenResourceOverlayTask
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

class MavenOverlayPlatformTest : MavenImportingTestCase() {

    // --- WAR overlay: dev profile ---
    fun testWarOverlay_DevProfile() {
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
            """<jboss-web><context-root>${'$'}{app.context}</context-root></jboss-web>""")

        importProjectWithProfiles("dev")

        val mavenProject = projectsManager.projects.first()
        assertNotNull("Maven project should be imported", mavenProject)

        val artifactOutput = Files.createTempDirectory("artifact-output")
        try {
            // Simulate IntelliJ's default assembly
            val webInf = artifactOutput.resolve("WEB-INF")
            Files.createDirectories(webInf)
            Files.writeString(webInf.resolve("jboss-web.xml"),
                """<jboss-web><context-root>${'$'}{app.context}</context-root></jboss-web>""")

            val task = MavenResourceOverlayTask(
                project = project,
                artifactOutputPath = artifactOutput,
                mavenProjectPath = mavenProject.directoryFile.toNioPath(),
                artifactType = ArtifactType.WAR,
                artifactName = "test-war"
            )
            task.execute()

            // Verify overlay file copied
            assertTrue("config.json should be overlaid",
                Files.exists(artifactOutput.resolve("WEB-INF/config.json")))
            val configContent = Files.readString(artifactOutput.resolve("WEB-INF/config.json"))
            assertTrue(configContent.contains("dev"))

            // Verify deployment descriptor filtered
            val jbossXml = Files.readString(webInf.resolve("jboss-web.xml"))
            assertTrue("jboss-web.xml should have app.context substituted",
                jbossXml.contains("myapp-dev"))
            assertFalse("No unresolved placeholders",
                jbossXml.contains("\${app.context}"))
        } finally {
            artifactOutput.toFile().deleteRecursively()
        }
    }

    // --- WAR overlay: prod profile ---
    fun testWarOverlay_ProdProfile() {
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
            """<jboss-web><context-root>${'$'}{app.context}</context-root></jboss-web>""")

        importProjectWithProfiles("prod")

        val mavenProject = projectsManager.projects.first()
        val artifactOutput = Files.createTempDirectory("artifact-output")
        try {
            val webInf = artifactOutput.resolve("WEB-INF")
            Files.createDirectories(webInf)
            Files.writeString(webInf.resolve("jboss-web.xml"),
                """<jboss-web><context-root>${'$'}{app.context}</context-root></jboss-web>""")

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

    // --- Profile switch: dev → prod, cache invalidation ---
    fun testWarOverlay_ProfileSwitch() {
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
            """<jboss-web><context-root>${'$'}{app.context}</context-root></jboss-web>""")

        // First build with dev
        importProjectWithProfiles("dev")
        val mavenProject = projectsManager.projects.first()
        val artifactOutput = Files.createTempDirectory("artifact-output")
        try {
            val webInf = artifactOutput.resolve("WEB-INF")
            Files.createDirectories(webInf)
            Files.writeString(webInf.resolve("jboss-web.xml"),
                """<jboss-web><context-root>${'$'}{app.context}</context-root></jboss-web>""")

            MavenResourceOverlayTask(project, artifactOutput,
                mavenProject.directoryFile.toNioPath(), ArtifactType.WAR, "test-war").execute()

            var jbossXml = Files.readString(webInf.resolve("jboss-web.xml"))
            assertTrue("First build should have dev context", jbossXml.contains("myapp-dev"))

            // Switch to prod — rewrite descriptor and re-execute
            Files.writeString(webInf.resolve("jboss-web.xml"),
                """<jboss-web><context-root>${'$'}{app.context}</context-root></jboss-web>""")

            importProjectWithProfiles("prod")
            MavenResourceOverlayTask(project, artifactOutput,
                mavenProject.directoryFile.toNioPath(), ArtifactType.WAR, "test-war").execute()

            jbossXml = Files.readString(webInf.resolve("jboss-web.xml"))
            assertTrue("Second build should have prod context", jbossXml.contains("<context-root>myapp</context-root>"))
            val configJson = Files.readString(artifactOutput.resolve("WEB-INF/config.json"))
            assertTrue("config.json should now be prod", configJson.contains("prod"))
        } finally {
            artifactOutput.toFile().deleteRecursively()
        }
    }

    // --- JAR filtering: dev profile ---
    fun testJarFiltering_DevProfile() {
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
        createProjectSubFile("src/main/resources/config.properties", "db.url=${'$'}{db.url}\n")

        importProjectWithProfiles("dev")

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

    // --- No Maven project at path — logs warning and returns ---
    fun testNoMavenProject_Skips() {
        createProjectPom("""
            <groupId>com.test</groupId>
            <artifactId>test</artifactId>
            <version>1.0</version>
        """)
        importProject()

        val artifactOutput = Files.createTempDirectory("artifact-output")
        try {
            // Use a path that doesn't correspond to any imported Maven project
            val bogusPath = Path.of("/tmp/nonexistent-project-${System.nanoTime()}")
            val task = MavenResourceOverlayTask(
                project = project,
                artifactOutputPath = artifactOutput,
                mavenProjectPath = bogusPath,
                artifactType = ArtifactType.WAR,
                artifactName = "test"
            )
            // Should not throw — just log warning and return
            task.execute()
            // If we get here, the test passes
        } finally {
            artifactOutput.toFile().deleteRecursively()
        }
    }

    // --- No active profiles — activeByDefault properties applied ---
    fun testNoActiveProfiles_UsesDefaults() {
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
        createProjectSubFile("src/main/resources/env.properties", "env=${'$'}{env.name}\n")

        // Import with no explicit profiles
        importProject()

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
}
```

**Note:** `MavenImportingTestCase` uses JUnit 3 test naming conventions — method names start with `test` and no `@Test` annotation is needed (it extends `TestCase` via `UsefulTestCase`).

- [ ] **Step 2: Run the platform tests**

Run: `./gradlew integrationTest`
Expected: All 6 platform tests pass. If `OverlayLog.getInstance(project)` or `OverlayNotifications` fails with a service/notification group error, add null-safety to those calls in the production code (see step 3).

- [ ] **Step 3: Fix service availability issues if needed**

If `OverlayNotifications.notifySuccess()` throws NPE because `NotificationGroupManager.getNotificationGroup()` returns null in tests, add a null check:

In `src/main/kotlin/ro/gs1s/mvnresfilter/OverlayNotifications.kt`, change each method to:
```kotlin
fun notifySuccess(project: Project, message: String) {
    NotificationGroupManager.getInstance()
        .getNotificationGroup(GROUP_ID)
        ?.createNotification(message, NotificationType.INFORMATION)
        ?.notify(project)
}
```

If `OverlayLog.getInstance(project)` returns null, the `MavenResourceOverlayTask` already handles this — `ResourceProcessor` accepts `OverlayLog? = null`. But `MavenResourceOverlayTask.execute()` calls `OverlayLog.getInstance(project)` directly. If it throws, change line 45 in `MavenResourceOverlayTask.kt` to:
```kotlin
val overlayLog = project.getServiceIfCreated(OverlayLog::class.java)
```

- [ ] **Step 4: Re-run if fixes were applied**

Run: `./gradlew integrationTest`
Expected: All 6 tests pass.

Run: `./gradlew test`
Expected: All 47 unit/component tests still pass.

- [ ] **Step 5: Commit**

```bash
git add src/integrationTest/kotlin/ro/gs1s/mvnresfilter/platform/MavenOverlayPlatformTest.kt
# Also add any production code fixes if applied:
# git add src/main/kotlin/ro/gs1s/mvnresfilter/OverlayNotifications.kt
# git add src/main/kotlin/ro/gs1s/mvnresfilter/MavenResourceOverlayTask.kt
git commit -m "Add platform integration tests via MavenImportingTestCase"
```

---

## Task 9: Final verification

- [ ] **Step 1: Run full test suite**

Run: `./gradlew test`
Expected: 47 tests pass (39 original + 8 new component tests)

- [ ] **Step 2: Run integration tests**

Run: `./gradlew integrationTest`
Expected: 6 platform tests pass

- [ ] **Step 3: Commit any final adjustments**

If any test needed adjustment during final verification.
