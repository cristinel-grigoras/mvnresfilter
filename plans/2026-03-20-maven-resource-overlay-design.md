# Maven Resource Overlay — IntelliJ Plugin Design

## Problem

IntelliJ IDEA's artifact builder does not read Maven's `maven-war-plugin` `<webResources>` configuration
from active profiles. Files like `keycloak.json`, `oidc.json` from profile directories and property
substitution in `jboss-web.xml`, `persistence.xml` are ignored. This is a known, long-standing IntelliJ
limitation (IDEA-25934). No existing plugin on the JetBrains Marketplace solves this.

## Reference Project for Analysis

The design is based on analysis of the codaloc workspace at:
`/home/grigoras/proiecte/codaloc/jworkspace/`

### Two WAR projects with the same pattern:

**codaloc** (`/home/grigoras/proiecte/codaloc/jworkspace/codaloc/pom.xml`):
- Profiles: `default`, `development`, `beta-gs1`, `prod-gs1`, `debuglibs`, `integration-tests`
- Profile resources (plain copy, no filtering):
  - `profiles/development/WEB-INF/oidc.json`
  - `profiles/development/WEB-INF/keycloak.json`
- Filtered files (need `${...}` substitution):
  - `src/main/webapp/WEB-INF/jboss-web.xml` — no placeholders in codaloc (hardcoded `/codaloc`)
  - `src/main/resources/META-INF/persistence.xml` — uses `${hibernate.hbm2ddl_auto}`, `${hibernate.show_sql}` (via `<resources><filtering>true`)
- Properties per profile: `buildNumber`, `hibernate.show_sql`, `org.gs1.runtime`, `javax.faces.stage`, `codaloc.siteurl`
- `<nonFilteredFileExtensions>`: pdf, png, jpg, gif, min.js, eot, ttf, svg, woff, woff2, xls, xlsx, properties
- `<filteringDeploymentDescriptors>true</filteringDeploymentDescriptors>` in profile war plugin configs

**clb-web** (`/home/grigoras/proiecte/codaloc/jworkspace/clb-web/pom.xml`):
- Profiles: `development`, `stage-gs1`, `beta-gs1`, `prod-gs1`
- Profile resources (plain copy):
  - `profiles/development/WEB-INF/keycloak.json`
- Filtered files:
  - `src/main/webapp/WEB-INF/jboss-web.xml` — uses `${jbossweb.context}` (e.g., `clb` vs `clbstage`)
  - `src/main/resources/META-INF/persistence.xml` — uses `${hibernate.hbm2ddl_auto}`, `${hibernate.show_sql}`
- clb-web has `<filtering>true</filtering>` on the `profiles/development` webResource entry
  (but the actual files contain no `${...}` placeholders currently — filtering is a no-op but must be supported)
- Properties per profile: `jbossweb.context`, `hibernate.show_sql`, `org.gs1.runtime`, `codaloc.siteurl`, `current.siteurl`

### What `mvn war:exploded -Pdevelopment` does (the target behavior):

```
1. Compile sources → target/*/WEB-INF/classes/
2. Process <resources> with <filtering>true</filtering>:
   - persistence.xml: replace ${hibernate.show_sql} → "true", ${hibernate.hbm2ddl_auto} → "update"
3. Copy src/main/webapp/ → target/*/
4. Process <webResources> in declaration order:
   a. Copy profiles/development/* → target/*/ (overlay WEB-INF/keycloak.json, oidc.json)
   b. Filter src/main/webapp/WEB-INF/jboss-web.xml → replace ${jbossweb.context} → "clb"
5. Apply <filteringDeploymentDescriptors> to web.xml, jboss-web.xml
6. Respect <nonFilteredFileExtensions> — skip binary files during filtering
```

## Solution: Approach 3 — Hybrid (Own Copy + maven-filtering Library)

### Why this approach

| Approach | Speed | Maven accuracy | Complexity |
|----------|-------|---------------|------------|
| 1. Reimplement filtering | Fast | Risk of divergence | Medium |
| 2. Invoke Maven process | Slow | 100% | Low |
| **3. Hybrid (maven-filtering lib)** | **Fast** | **100%** | **Medium** |

Use `ArtifactBuildTaskProvider` to hook into IntelliJ's artifact assembly. For file copying, implement
directly with includes/excludes support. For `${...}` property substitution, use
`org.apache.maven.shared:maven-filtering` — the same library Maven plugins use internally.

## Plugin Identity

- **Name:** Maven Resource Overlay
- **Plugin ID:** `ro.gs1s.mvnresfilter.mvnresfilter`
- **Marketplace ID:** `ro.gs1s.mvnresfilter`
- **Target:** IntelliJ IDEA 2025.2+, Java 21, Kotlin
- **Plugin project:** `/home/grigoras/proiecte/ideadevel/mvnresfilter/`

## Architecture

### Project Structure

```
mvnresfilter/
├── build.gradle.kts
├── settings.gradle.kts
├── src/main/
│   ├── kotlin/ro/gs1s/mvnresfilter/
│   │   ├── MavenResourceOverlayTaskProvider.kt  # ArtifactBuildTaskProvider — registers the task
│   │   ├── MavenResourceOverlayTask.kt          # The build task — orchestrates the pipeline
│   │   ├── MavenModelReader.kt                  # Reads pom: profiles, properties, webResources, resources
│   │   ├── ResourceProcessor.kt                 # Copy + filter logic using maven-filtering
│   │   ├── OverlayConfig.kt                     # Data classes for parsed configuration
│   │   └── notifications/
│   │       └── OverlayNotifications.kt           # IDE notification support (errors, warnings)
│   └── resources/
│       ├── META-INF/plugin.xml
│       └── messages/MyMessageBundle.properties
├── src/test/kotlin/...
└── plans/
```

### Dependencies

```kotlin
// build.gradle.kts additions
dependencies {
    intellijPlatform {
        bundledPlugin("org.jetbrains.idea.maven")  // Maven integration API
    }
    implementation("org.apache.maven.shared:maven-filtering:3.3.1")
}
```

### Extension Registration (plugin.xml)

```xml
<depends>org.jetbrains.idea.maven</depends>

<extensions defaultExtensionNs="com.intellij">
    <!-- Hook into artifact build -->
    <artifactBuildTaskProvider
        implementation="ro.gs1s.mvnresfilter.MavenResourceOverlayTaskProvider"/>
</extensions>
```

## Build Task Pipeline

### Trigger

`ArtifactBuildTaskProvider` checks if the artifact is an exploded WAR from a Maven project.
If yes, it provides `MavenResourceOverlayTask` which runs **after** IntelliJ's default artifact assembly.

### Pipeline Steps

```
IntelliJ builds exploded WAR (default behavior)
  → classes compiled to WEB-INF/classes/
  → webapp resources copied from src/main/webapp/
  → dependencies copied to WEB-INF/lib/

MavenResourceOverlayTask runs (post-process):

  Step 1: Read Maven context
    - Get MavenProject from MavenProjectsManager
    - Get active profiles via getActivatedProfilesIds().getEnabledProfiles()
    - Merge properties: project properties + all active profile properties (later overrides earlier)

  Step 2: Parse war plugin configuration
    - For each active profile that has maven-war-plugin config:
      - Collect <webResources> entries (directory, includes, excludes, filtering flag)
      - Collect <nonFilteredFileExtensions>
      - Check <filteringDeploymentDescriptors>
    - Merge across active profiles in pom declaration order

  Step 3: Process <resources> → WEB-INF/classes/
    - For each <resource> with <filtering>true</filtering>:
      - Find matching files (respecting includes/excludes)
      - Apply ${...} substitution using merged properties
      - Write to artifact's WEB-INF/classes/ (overwriting IntelliJ's unfiltered copy)
    - Example: persistence.xml with ${hibernate.show_sql} → "true"

  Step 4: Process <webResources> → WAR root
    - For each webResource entry:
      - Resolve directory path (relative to project basedir)
      - Copy files to artifact root (respecting includes/excludes)
      - If filtering=true on the webResource: apply ${...} substitution
      - Respect <nonFilteredFileExtensions> — binary copy for matching extensions
    - Example: profiles/development/WEB-INF/keycloak.json → copied as-is
    - Example: jboss-web.xml with ${jbossweb.context} → filtered

  Step 5: Filter deployment descriptors (if enabled)
    - If <filteringDeploymentDescriptors>true</filteringDeploymentDescriptors>:
      - Filter WEB-INF/web.xml, WEB-INF/jboss-web.xml in the artifact output
      - Apply ${...} substitution using merged properties
```

## Key Components

### MavenModelReader

Responsible for extracting all configuration from the Maven project model.

```kotlin
data class OverlayConfig(
    val activeProfiles: List<String>,
    val mergedProperties: Map<String, String>,
    val webResources: List<WebResourceDef>,
    val resources: List<ResourceDef>,
    val nonFilteredExtensions: Set<String>,
    val filterDeploymentDescriptors: Boolean,
    val projectBasedir: Path,
    val artifactOutputPath: Path
)

data class WebResourceDef(
    val directory: String,        // e.g., "profiles/development"
    val includes: List<String>,   // e.g., ["**/jboss-web.xml"]
    val excludes: List<String>,
    val filtering: Boolean
)

data class ResourceDef(
    val directory: String,        // e.g., "src/main/resources"
    val includes: List<String>,
    val excludes: List<String>,
    val filtering: Boolean,
    val targetPath: String?       // relative output path
)
```

**Property merge order (matching Maven):**
1. Project-level `<properties>`
2. Active profile `<properties>` in pom declaration order (later profiles win)
3. Built-in properties: `project.artifactId`, `project.version`, `project.basedir`, `basedir`

### ResourceProcessor

Uses `maven-filtering` library for substitution:

```kotlin
class ResourceProcessor(private val config: OverlayConfig) {

    fun processResources() {
        // Filter <resources> entries to WEB-INF/classes/
        for (resource in config.resources) {
            if (!resource.filtering) continue
            processDirectory(
                sourceDir = config.projectBasedir.resolve(resource.directory),
                targetDir = config.artifactOutputPath.resolve("WEB-INF/classes"),
                includes = resource.includes,
                excludes = resource.excludes,
                filter = true
            )
        }
    }

    fun processWebResources() {
        // Copy/filter <webResources> entries to WAR root
        for (webRes in config.webResources) {
            processDirectory(
                sourceDir = config.projectBasedir.resolve(webRes.directory),
                targetDir = config.artifactOutputPath,
                includes = webRes.includes,
                excludes = webRes.excludes,
                filter = webRes.filtering
            )
        }
    }

    fun filterDeploymentDescriptors() {
        if (!config.filterDeploymentDescriptors) return
        val webInf = config.artifactOutputPath.resolve("WEB-INF")
        filterFileInPlace(webInf.resolve("web.xml"))
        filterFileInPlace(webInf.resolve("jboss-web.xml"))
    }

    private fun processDirectory(sourceDir: Path, targetDir: Path,
                                  includes: List<String>, excludes: List<String>,
                                  filter: Boolean) {
        // Walk source directory
        // Match files against includes/excludes patterns
        // For each file:
        //   if filter && extension not in nonFilteredExtensions:
        //     read → substitute ${...} → write to target
        //   else:
        //     binary copy to target
    }

    private fun filterContent(content: String): String {
        // Use maven-filtering MavenResourcesFiltering or manual Pattern replacement
        // Pattern: \$\{([^}]+)\}
        // Replace with config.mergedProperties[key] or leave as-is if not found
    }
}
```

### Notifications

- **Success:** Balloon notification: "Maven Resource Overlay: processed N files for profile [development]"
- **Warning:** If a `${...}` placeholder has no matching property: log warning, leave placeholder as-is
- **Error:** If webResource directory doesn't exist: show error notification, continue with other resources

## Edge Cases

1. **No active profiles** — only process project-level config (main `<build>` section)
2. **Profile has no war plugin config** — skip it, only merge its properties
3. **Multiple profiles with webResources** — merge in pom declaration order
4. **`<activeByDefault>true</activeByDefault>`** — respect Maven semantics: deactivated when any profile is explicitly activated
5. **Non-WAR artifacts** — `ArtifactBuildTaskProvider` skips non-WAR artifacts
6. **Incremental builds** — always re-run overlay (profile resources are small, filtering must reflect current properties)
7. **Missing source directory** — warn and skip, don't fail the build

## Testing Strategy

- **Unit tests:** `MavenModelReader` parsing with sample pom.xml fragments
- **Unit tests:** `ResourceProcessor` filtering (property substitution, nonFilteredExtensions, includes/excludes)
- **Integration tests:** Full pipeline with a test Maven project, verify output files
- **Manual testing:** Against codaloc and clb-web projects in the reference workspace

## Future Enhancements

- Support `@property@` delimiter style (used by Spring Boot)
- Tool window showing overlay status per artifact
- Automatic re-overlay when Maven profile selection changes