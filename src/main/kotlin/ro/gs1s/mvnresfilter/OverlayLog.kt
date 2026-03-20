package ro.gs1s.mvnresfilter

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Project-level service that collects overlay processing messages
 * and notifies the tool window to update.
 */
@Service(Service.Level.PROJECT)
class OverlayLog {

    private val lines = mutableListOf<String>()
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()

    fun addListener(listener: (String) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (String) -> Unit) {
        listeners.remove(listener)
    }

    fun header(artifactName: String, profiles: List<String>, outputPath: String) {
        append("── Maven Resource Overlay [${profiles.joinToString(", ")}] ──")
        append("Artifact: $artifactName → $outputPath")
    }

    fun filtered(relativePath: String, propertiesReplaced: Int) {
        append("Filtered: $relativePath ($propertiesReplaced properties replaced)")
    }

    fun copied(relativePath: String) {
        append("Copied:   $relativePath")
    }

    fun skipped(message: String) {
        append("Skipped:  $message")
    }

    fun done(fileCount: Int) {
        append("Done: $fileCount files processed")
        append("")
    }

    fun cacheHit(artifactName: String) {
        append("Cache hit: $artifactName — skipping (no changes)")
        append("")
    }

    fun clear() {
        lines.clear()
    }

    fun getLines(): List<String> = lines.toList()

    private fun append(line: String) {
        lines.add(line)
        for (listener in listeners) {
            listener(line)
        }
    }

    companion object {
        fun getInstance(project: Project): OverlayLog {
            return project.getService(OverlayLog::class.java)
        }
    }
}
