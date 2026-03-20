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
        log.debug("Processing ${mavenProject.displayName}, pom=$pomPath, profiles=$activeProfiles, output=$artifactOutputPath, type=$artifactType")
        val config = reader.buildConfig(pomPath, activeProfiles, artifactOutputPath, artifactType)
        log.debug("projectBasedir=${config.projectBasedir}, resources=${config.resources.map { it.directory }}, webResources=${config.webResources.map { it.directory }}")

        // Cache stored in project's target/ directory, not inside artifact output
        val cacheDir = config.projectBasedir.resolve("target")
        val cache = OverlayCache(cacheDir, artifactName)
        val cacheInputs = buildCacheInputs(config)

        val overlayLog = OverlayLog.getInstance(project)

        if (cache.isUpToDate(cacheInputs)) {
            log.debug("Skipping — cache is up to date for $artifactName")
            overlayLog.cacheHit(artifactName)
            return
        }

        overlayLog.header(artifactName, activeProfiles, artifactOutputPath.toString())

        val startTime = System.currentTimeMillis()
        val processor = ResourceProcessor(config, overlayLog)

        try {
            processor.processResources()
            processor.processWebResources()
            processor.filterDeploymentDescriptors()

            val elapsedMs = System.currentTimeMillis() - startTime
            val fileCount = processor.getFileCount()
            val fileErrors = processor.getErrors()
            val profileNames = config.activeProfiles.joinToString(", ")

            if (fileErrors.isEmpty()) {
                cache.writeCache(cacheInputs)
                overlayLog.done(fileCount, elapsedMs)
                OverlayNotifications.notifySuccess(
                    project,
                    "Maven Resource Overlay: processed $fileCount files for [$profileNames] in ${elapsedMs}ms"
                )
            } else {
                // Don't cache if there were errors — reprocess next time
                overlayLog.done(fileCount, elapsedMs)
                OverlayNotifications.notifyWarning(
                    project,
                    "Maven Resource Overlay: processed $fileCount files for [$profileNames] in ${elapsedMs}ms, ${fileErrors.size} file(s) failed"
                )
            }
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
