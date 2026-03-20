package ro.gs1s.mvnresfilter

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.packaging.artifacts.ArtifactManager
import com.intellij.task.ProjectTaskListener
import com.intellij.task.ProjectTaskManager
import org.jetbrains.idea.maven.project.MavenProjectsManager
import java.nio.file.Files
import java.nio.file.Path

class MavenResourceOverlayBuildListener(private val project: Project) : ProjectTaskListener {

    private val log = Logger.getInstance(MavenResourceOverlayBuildListener::class.java)

    override fun finished(result: ProjectTaskManager.Result) {
        if (result.isAborted || result.hasErrors()) return

        val mavenProjectsManager = MavenProjectsManager.getInstance(project)
        if (!mavenProjectsManager.isMavenizedProject) return

        val artifacts = ArtifactManager.getInstance(project).artifacts
        if (artifacts.isEmpty()) return

        for (artifact in artifacts) {
            val outputPath = artifact.outputPath ?: continue
            val typeId = artifact.artifactType.id

            log.info("Maven Resource Overlay: checking artifact '${artifact.name}', typeId='$typeId', output=$outputPath")

            // Only process exploded artifacts — archives are built from exploded, no need to process both
            val artifactType = when (typeId) {
                "exploded-war" -> ArtifactType.WAR
                "exploded-ear" -> ArtifactType.WAR  // EAR uses same overlay logic
                "jar" -> ArtifactType.JAR
                else -> {
                    log.info("Maven Resource Overlay: skipping artifact '${artifact.name}' — type '$typeId' not supported (only exploded artifacts)")
                    continue
                }
            }

            // Find the Maven project that owns this artifact
            val mavenProject = findMavenProjectForArtifact(mavenProjectsManager, artifact.name)
                ?: continue

            try {
                val task = MavenResourceOverlayTask(
                    project = project,
                    artifactOutputPath = Path.of(outputPath),
                    mavenProjectPath = mavenProject.directoryFile.toNioPath(),
                    artifactType = artifactType,
                    artifactName = artifact.name
                )
                task.execute()
            } catch (e: Exception) {
                log.error("Maven Resource Overlay failed for artifact '${artifact.name}'", e)
            }
        }
    }

    private fun findMavenProjectForArtifact(
        manager: MavenProjectsManager,
        artifactName: String
    ): org.jetbrains.idea.maven.project.MavenProject? {
        // Match artifact name to Maven project by artifactId
        return manager.projects.find { mavenProject ->
            val artifactId = mavenProject.mavenId.artifactId ?: return@find false
            artifactName.contains(artifactId, ignoreCase = true)
        }
    }
}
