package ro.gs1s.mvnresfilter

import com.intellij.build.BuildViewManager
import com.intellij.build.DefaultBuildDescriptor
import com.intellij.build.events.MessageEvent
import com.intellij.build.events.impl.*
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

/**
 * Posts overlay processing messages into the Build tool window
 * as a separate build entry ("Maven Resource Overlay").
 *
 * Each overlay run starts a new build entry via StartBuildEvent,
 * posts MessageEvents for each file action, then closes with FinishBuildEvent.
 */
@Service(Service.Level.PROJECT)
class OverlayLog(private val project: Project) {

    private var currentBuildId: Any? = null

    fun header(artifactName: String, profiles: List<String>, outputPath: String) {
        val buildId = "maven-resource-overlay-${System.currentTimeMillis()}"
        currentBuildId = buildId

        val title = "Maven Resource Overlay [${ profiles.joinToString(", ") }]"
        val descriptor = DefaultBuildDescriptor(
            buildId, title, outputPath, System.currentTimeMillis()
        )

        val startEvent = StartBuildEventImpl(descriptor, "Processing $artifactName...")
        getBuildView()?.onEvent(buildId, startEvent)

        message("Artifact: $artifactName → $outputPath", MessageEvent.Kind.INFO)
    }

    fun filtered(relativePath: String, propertiesReplaced: Int) {
        message("Filtered: $relativePath ($propertiesReplaced properties replaced)", MessageEvent.Kind.INFO)
    }

    fun copied(relativePath: String) {
        message("Copied:   $relativePath", MessageEvent.Kind.INFO)
    }

    fun skipped(msg: String) {
        message("Skipped:  $msg", MessageEvent.Kind.WARNING)
    }

    fun done(fileCount: Int) {
        val buildId = currentBuildId ?: return
        message("Done: $fileCount files processed", MessageEvent.Kind.INFO)

        val finishEvent = FinishBuildEventImpl(
            buildId, null, System.currentTimeMillis(), "completed",
            SuccessResultImpl()
        )
        getBuildView()?.onEvent(buildId, finishEvent)
        currentBuildId = null
    }

    fun cacheHit(artifactName: String) {
        val buildId = "maven-resource-overlay-cache-${System.currentTimeMillis()}"
        currentBuildId = buildId

        val descriptor = DefaultBuildDescriptor(
            buildId, "Maven Resource Overlay", "", System.currentTimeMillis()
        )
        val startEvent = StartBuildEventImpl(descriptor, "Cache hit: $artifactName")
        getBuildView()?.onEvent(buildId, startEvent)

        message("Cache hit: $artifactName — skipping (no changes)", MessageEvent.Kind.INFO)

        val finishEvent = FinishBuildEventImpl(
            buildId, null, System.currentTimeMillis(), "up-to-date",
            SuccessResultImpl()
        )
        getBuildView()?.onEvent(buildId, finishEvent)
        currentBuildId = null
    }

    private fun message(text: String, kind: MessageEvent.Kind) {
        val buildId = currentBuildId ?: return
        val event = MessageEventImpl(
            buildId, kind, "Maven Resource Overlay", text, null
        )
        getBuildView()?.onEvent(buildId, event)
    }

    private fun getBuildView(): BuildViewManager? {
        return project.getService(BuildViewManager::class.java)
    }

    companion object {
        fun getInstance(project: Project): OverlayLog {
            return project.getService(OverlayLog::class.java)
        }
    }
}
