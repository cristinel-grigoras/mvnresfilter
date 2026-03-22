# Integration Testing Design

## Overview

Add two tiers of integration tests to the Maven Resource Overlay plugin:

1. **Platform integration tests** — extend `MavenImportingTestCase`, run a real IntelliJ project with Maven imported, verify the full plugin pipeline through IntelliJ's APIs.
2. **Expanded component integration tests** — plain JUnit 4 tests covering error handling, profile combinations, filter files, and multi-module edge cases.

## Motivation

The existing 39 tests (16 MavenModelReader, 11 ResourceProcessor, 6 OverlayCache, 6 integration) cover unit logic and basic end-to-end wiring, but:
- No test runs against IntelliJ's actual `MavenProjectsManager` (standalone XPP3 parsing only)
- Error/edge cases (malformed POM, empty profiles) are under-tested
- Multi-profile webResources overlay (multiple profiles contributing files, overlapping files) lack coverage
- Multi-module wiring through full pipeline has limited coverage

## Gradle Setup — Separate `integrationTest` Source Set

A new `integrationTest` source set keeps heavyweight platform tests separate from the fast unit/component test suite.

```kotlin
// build.gradle.kts additions

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

dependencies {
    intellijPlatform {
        testFramework(TestFrameworkType.Plugin.Maven,
            configurationName = "integrationTestImplementation")
    }
}

tasks.register<Test>("integrationTest") {
    testClassesDirs = sourceSets["integrationTest"].output.classesDirs
    classpath = sourceSets["integrationTest"].runtimeClasspath
}
```

**Important:** The IntelliJ Platform Gradle Plugin auto-wires the standard `test` source set with the platform classpath. A custom `integrationTest` source set may need additional classpath wiring to pick up IDE JARs and bundled plugin JARs. **Implementation step 1 must prototype this setup** with a trivial test that compiles and runs before building out the full test suite. If the custom source set proves too fragile, the fallback is to place platform tests in `src/test/` with a naming convention (`*PlatformTest`) and use Gradle test filtering:

```kotlin
tasks.test { exclude("**/platform/**") }
tasks.register<Test>("integrationTest") {
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    include("**/platform/**")
}
```

**Commands:**
- `./gradlew test` — runs unit + component tests (fast, ~5s)
- `./gradlew integrationTest` — runs platform tests only (~30-60s)

## Platform Integration Tests

**Location:** `src/integrationTest/kotlin/ro/gs1s/mvnresfilter/platform/`

**Base class:** `com.intellij.maven.testFramework.MavenImportingTestCase` (from `TestFrameworkType.Plugin.Maven`, artifact `com.jetbrains.intellij.maven:maven-test-framework`)
- Extends `MavenTestCase` → `UsefulTestCase` (JUnit 4)
- Provides a real IntelliJ project with Maven integration
- `createProjectPom(xml)` / `createProjectSubFile()` for file creation
- `importProjectWithProfiles(vararg profiles: String)` for Maven import with active profiles
- `projectsManager` property for `MavenProjectsManager` access
- Prefer async import variants (`importProjectAsync()`) — sync methods are marked `@Obsolete`

**Service availability:** `MavenResourceOverlayTask.execute()` calls `OverlayLog.getInstance(project)` and `OverlayNotifications.notifySuccess()`. These use `project.getService()` and `Notifications.Bus.notify()`. Both should work in the `MavenImportingTestCase` environment since it provides a fully initialized project. If `OverlayLog` service registration fails in tests, the fallback is to call the task with a `null`-safe log wrapper or to make `OverlayLog` optional.

### Test class: `MavenOverlayPlatformTest`

**Test flow for each test:**
1. `createProjectPom(xml)` — write pom.xml with profiles, webResources, property substitution
2. Create source files (profile dirs, webapp descriptors) via `createProjectSubFile()`
3. `importProjectWithProfiles("dev")` — triggers real Maven import
4. Verify `projectsManager.projects` resolves the model correctly
5. Create a temp artifact output dir, copy deployment descriptors into it (simulating IntelliJ's default WAR assembly)
6. Call `MavenResourceOverlayTask(project, artifactOutputPath, mavenProjectPath, ArtifactType.WAR, "test").execute()`
7. Assert output files: overlays copied, properties substituted, deployment descriptors filtered

No server configuration needed — artifacts are created programmatically, and the plugin only reacts to `ProjectTaskListener.finished()` / reads `ArtifactManager.getArtifacts()`. For these tests we call `MavenResourceOverlayTask.execute()` directly.

### Test cases (6 tests)

| Test | What it verifies |
|------|-----------------|
| `testWarOverlay_DevProfile` | Full pipeline: profile import → webResources overlay → property substitution |
| `testWarOverlay_ProdProfile` | Same pipeline with different profile, different property values |
| `testWarOverlay_ProfileSwitch` | Import with dev, process, then switch to prod — verifies cache invalidation |
| `testJarFiltering_DevProfile` | JAR resources filtered through IntelliJ's resolved Maven model |
| `testNoMavenProject_Skips` | Non-Maven project directory — task logs warning and returns without error |
| `testNoActiveProfiles_UsesDefaults` | No explicit profiles — activeByDefault profile properties applied |

**Key difference from existing tests:** Existing integration tests use `MavenModelReader` (standalone XPP3 parsing). Platform tests verify `MavenResourceOverlayTask.execute()` which reads the project through `MavenProjectsManager`.

## Expanded Component Integration Tests

**Location:** `src/test/kotlin/ro/gs1s/mvnresfilter/integration/` (alongside existing tests, runs with `./gradlew test`)

These don't need the IntelliJ platform — they test `MavenModelReader` + `ResourceProcessor` + `OverlayCache` wired together against fixture projects, covering scenarios not already tested by unit tests.

**Note on redundancy:** Several scenarios (filter file priority, activeByDefault semantics, missing source directories, missing filter files) are already thoroughly tested in `MavenModelReaderTest` and `ResourceProcessorTest` at the unit level. The component tests below focus on **new wiring scenarios** — where multiple components interact to produce file output, or where the existing unit tests don't cover the behavior.

### ErrorHandlingIntegrationTest (3 tests)

| Test | What it verifies |
|------|-----------------|
| `testMissingPomXml_ThrowsOrSkips` | `MavenModelReader.buildConfig()` with non-existent pom path |
| `testMalformedPomXml_ThrowsParseError` | Truncated/invalid XML in pom.xml |
| `testEmptyProfile_NoWebResources` | Profile exists but has no war-plugin config — MavenModelReader → ResourceProcessor wiring produces no output, no crash |

### ProfileCombinationsIntegrationTest (2 tests)

| Test | What it verifies |
|------|-----------------|
| `testMultipleActiveProfiles_WebResourcesCombined` | Two profiles with webResources — both sets of overlay files appear in artifact output (wires MavenModelReader → ResourceProcessor) |
| `testOverlappingWebResources_LastProfileWins` | Two profiles write same file to same target — last profile in pom order wins in actual file output |

### MultiModuleIntegrationTest (3 tests)

| Test | What it verifies |
|------|-----------------|
| `testDeepInheritance_ParentProperties` | Parent defines properties, child profile overrides some — correct property substitution in output files |
| `testChildOverridesParentProperty` | Same property key in parent and child — child wins in actual file content |
| `testParentProfile_ChildProfile_BothActive` | Profiles from both parent and child contribute properties — wired through to file output |

## Test Fixtures

### New fixture projects (under `src/test/resources/projects/`)

- **`malformed-pom/`** — truncated/invalid pom.xml for error handling tests
- **`multi-profile/`** — pom with 2+ profiles having webResources and overlapping files

### Expected outputs (under `src/test/resources/expected/`)

New expected output directories for multi-profile scenarios where file content assertions need golden files.

## Test Utilities — Shared Helpers

Extract duplicated helpers from the 3 existing integration test files into a shared utility.

**Location:** `src/test/kotlin/ro/gs1s/mvnresfilter/integration/TestUtils.kt`

```kotlin
object TestUtils {
    val PROJECTS_BASE: Path = Path.of("src/test/resources/projects")
    val EXPECTED_BASE: Path = Path.of("src/test/resources/expected")

    fun copyTree(source: Path, target: Path)
    fun readText(path: Path): String
    fun compareTree(expectedDir: Path, actualDir: Path)
    fun resolveRelativePaths(config: OverlayConfig): OverlayConfig
    fun buildCacheInputs(config: OverlayConfig): OverlayCacheInputs
}
```

Existing integration tests (`WarOverlayIntegrationTest`, `JarFilteringIntegrationTest`, `IncrementalBuildTest`) refactored to use `TestUtils`.

## Changes to Existing Code

- `build.gradle.kts` — add integrationTest source set, task, Maven test framework dependency
- 3 existing integration test files — refactored to use `TestUtils` (no behavior change)

## Test Counts

| Category | Location | Gradle task | Count |
|----------|----------|-------------|-------|
| Platform tests | `src/integrationTest/kotlin/.../platform/` | `integrationTest` | 6 |
| Error handling | `src/test/kotlin/.../integration/` | `test` | 3 |
| Profile combos | `src/test/kotlin/.../integration/` | `test` | 2 |
| Multi-module | `src/test/kotlin/.../integration/` | `test` | 3 |

**Total: 14 new tests** (6 platform + 8 component)
