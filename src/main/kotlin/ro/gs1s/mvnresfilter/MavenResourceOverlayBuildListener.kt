package ro.gs1s.mvnresfilter

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.packaging.artifacts.ArtifactManager
import com.intellij.task.ProjectTaskListener
import com.intellij.task.ProjectTaskManager
import org.jetbrains.idea.maven.project.MavenProjectsManager
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
            val artifactTypeName = artifact.artifactType.id

            // Only process WAR and JAR exploded artifacts
            val artifactType = when {
                artifactTypeName.contains("war", ignoreCase = true) -> ArtifactType.WAR
                artifactTypeName.contains("jar", ignoreCase = true) -> ArtifactType.JAR
                else -> continue
            }

            // Find the Maven project that owns this artifact
            val mavenProject = findMavenProjectForArtifact(mavenProjectsManager, artifact.name)
                ?: continue

            try {
                val task = MavenResourceOverlayTask(
                    project = project,
                    artifactOutputPath = Path.of(outputPath),
                    mavenProjectPath = mavenProject.directoryFile.toNioPath(),
                    artifactType = artifactType
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
