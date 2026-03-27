package ro.gs1s.mvnresfilter

import com.intellij.build.BuildDescriptor
import com.intellij.build.BuildViewManager
import com.intellij.build.DefaultBuildDescriptor
import com.intellij.build.events.MessageEvent
import com.intellij.build.progress.BuildProgress
import com.intellij.build.progress.BuildProgressDescriptor
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

/**
 * Posts overlay processing messages into the Build tool window
 * as a separate build entry ("Maven Resource Overlay").
 *
 * Uses the BuildProgress API to manage build lifecycle:
 * start → message* → finish/fail.
 *
 * Status events (header, done, skipped) appear in the message tree.
 * File operation details (filtered, copied) are written to the output
 * panel (third zone) like IntelliJ's integrated build.
 */
@Service(Service.Level.PROJECT)
class OverlayLog(private val project: Project) {

    private var buildProgress: BuildProgress<BuildProgressDescriptor>? = null

    fun header(artifactName: String, profiles: List<String>, outputPath: String) {
        val title = "Maven Resource Overlay [${profiles.joinToString(", ")}]"
        val descriptor = DefaultBuildDescriptor(
            "maven-resource-overlay-${System.currentTimeMillis()}", title, outputPath, System.currentTimeMillis()
        )

        val progress = BuildViewManager.createBuildProgress(project)
        progress.start(SimpleBuildProgressDescriptor(title, descriptor))
        buildProgress = progress

        message("Artifact: $artifactName \u2192 $outputPath", MessageEvent.Kind.INFO)
    }

    fun filtered(relativePath: String, propertiesReplaced: Int) {
        buildProgress?.message(relativePath, "$propertiesReplaced properties replaced", MessageEvent.Kind.INFO, null)
        output("Filtered: $relativePath ($propertiesReplaced properties replaced)\n")
    }

    fun copied(relativePath: String) {
        buildProgress?.message(relativePath, "copied", MessageEvent.Kind.INFO, null)
        output("Copied: $relativePath\n")
    }

    fun skipped(msg: String) {
        message("Skipped:  $msg", MessageEvent.Kind.WARNING)
        output("Skipped:  $msg\n")
    }

    fun done(fileCount: Int, elapsedMs: Long = 0) {
        val timeStr = if (elapsedMs > 0) " in ${elapsedMs}ms" else ""
        message("Done: $fileCount files processed$timeStr", MessageEvent.Kind.INFO)
        buildProgress?.finish()
        buildProgress = null
    }

    fun cacheHit(artifactName: String) {
        val title = "Maven Resource Overlay"
        val descriptor = DefaultBuildDescriptor(
            "maven-resource-overlay-cache-${System.currentTimeMillis()}", title, "", System.currentTimeMillis()
        )

        val progress = BuildViewManager.createBuildProgress(project)
        progress.start(SimpleBuildProgressDescriptor(title, descriptor))
        progress.message("Maven Resource Overlay", "Cache hit: $artifactName \u2014 skipping (no changes)", MessageEvent.Kind.INFO, null)
        progress.finish(true)
    }

    private fun message(text: String, kind: MessageEvent.Kind) {
        buildProgress?.message("Maven Resource Overlay", text, kind, null)
    }

    private fun output(text: String) {
        buildProgress?.output(text, true)
    }

    companion object {
        fun getInstance(project: Project): OverlayLog {
            return project.getService(OverlayLog::class.java)
        }
    }
}

private class SimpleBuildProgressDescriptor(
    private val title: String,
    private val descriptor: BuildDescriptor,
) : BuildProgressDescriptor {
    override fun getTitle(): String = title
    override fun getBuildDescriptor(): BuildDescriptor = descriptor
}
