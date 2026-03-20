package ro.gs1s.mvnresfilter

import java.nio.file.Path

enum class ArtifactType { JAR, WAR }

data class OverlayConfig(
    val artifactType: ArtifactType,
    val activeProfiles: List<String>,
    val mergedProperties: Map<String, String>,
    val webResources: List<WebResourceDef>,
    val resources: List<ResourceDef>,
    val nonFilteredExtensions: Set<String>,
    val filterDeploymentDescriptors: Boolean,
    val projectBasedir: Path,
    val artifactOutputPath: Path
) {
    /** Where filtered <resources> go: WAR → WEB-INF/classes/, JAR → artifact root */
    val resourceOutputDir: Path
        get() = when (artifactType) {
            ArtifactType.WAR -> artifactOutputPath.resolve("WEB-INF/classes")
            ArtifactType.JAR -> artifactOutputPath
        }
}

data class WebResourceDef(
    val directory: String,
    val includes: List<String>,
    val excludes: List<String>,
    val filtering: Boolean
)

data class ResourceDef(
    val directory: String,
    val includes: List<String>,
    val excludes: List<String>,
    val filtering: Boolean,
    val targetPath: String?
)

data class OverlayCacheInputs(
    val profiles: List<String>,
    val properties: Map<String, String>,
    val sourceFiles: Map<String, Long>
)
