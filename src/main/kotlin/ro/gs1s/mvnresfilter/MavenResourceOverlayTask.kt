package ro.gs1s.mvnresfilter

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import org.jetbrains.idea.maven.project.MavenProject
import org.jetbrains.idea.maven.project.MavenProjectsManager
import java.nio.file.Files
import java.nio.file.Path

class MavenResourceOverlayTask(
    private val project: Project,
    private val artifactOutputPath: Path,
    private val mavenProjectPath: Path,
    private val artifactType: ArtifactType,
    private val artifactName: String = ""
) {
    private val log = Logger.getInstance(MavenResourceOverlayTask::class.java)

    fun execute() {
        val mavenProjectsManager = MavenProjectsManager.getInstance(project)
        val mavenProject = mavenProjectsManager.projects.find {
            it.directoryFile.toNioPath() == mavenProjectPath ||
            it.file.toNioPath() == mavenProjectPath.resolve("pom.xml")
        }

        if (mavenProject == null) {
            log.warn("Maven project not found at $mavenProjectPath")
            return
        }

        val explicitProfiles = mavenProjectsManager.explicitProfiles
        val activeProfiles = explicitProfiles.enabledProfiles.toList()

        val reader = MavenModelReader()
        val pomPath = mavenProject.file.toNioPath()
        log.info("Maven Resource Overlay: processing ${mavenProject.displayName}, pom=$pomPath, profiles=$activeProfiles, output=$artifactOutputPath, type=$artifactType")
        val config = reader.buildConfig(pomPath, activeProfiles, artifactOutputPath, artifactType)
        log.info("Maven Resource Overlay: projectBasedir=${config.projectBasedir}, resources=${config.resources.map { it.directory }}, webResources=${config.webResources.map { it.directory }}")

        // Cache stored in project's target/ directory, not inside artifact output
        val cacheDir = config.projectBasedir.resolve("target")
        val cache = OverlayCache(cacheDir, artifactName)
        val cacheInputs = buildCacheInputs(config)
        if (cache.isUpToDate(cacheInputs)) {
            log.info("Maven Resource Overlay: skipping — cache is up to date")
            return
        }

        val processor = ResourceProcessor(config)
        var processedCount = 0

        try {
            processor.processResources()
            processedCount += config.resources.count { it.filtering }

            processor.processWebResources()
            processedCount += config.webResources.size

            processor.filterDeploymentDescriptors()
            if (config.filterDeploymentDescriptors) processedCount += 2

            cache.writeCache(cacheInputs)

            val profileNames = config.activeProfiles.joinToString(", ")
            OverlayNotifications.notifySuccess(
                project,
                "Maven Resource Overlay: processed $processedCount resource groups for profile(s) [$profileNames]"
            )
        } catch (e: Exception) {
            log.error("Maven Resource Overlay failed", e)
            OverlayNotifications.notifyError(
                project,
                "Maven Resource Overlay failed: ${e.message}"
            )
        }
    }

    private fun buildCacheInputs(config: OverlayConfig): OverlayCacheInputs {
        val sourceFiles = mutableMapOf<String, Long>()
        val directories = config.resources.map { config.projectBasedir.resolve(it.directory) } +
                config.webResources.map { config.projectBasedir.resolve(it.directory) }

        for (dir in directories) {
            if (!Files.isDirectory(dir)) continue
            Files.walk(dir).use { stream ->
                stream.filter { Files.isRegularFile(it) }.forEach { file ->
                    sourceFiles[file.toString()] = Files.getLastModifiedTime(file).toMillis()
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
