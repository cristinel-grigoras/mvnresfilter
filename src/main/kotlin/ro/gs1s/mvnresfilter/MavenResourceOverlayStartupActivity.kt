package ro.gs1s.mvnresfilter

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.task.ProjectTaskListener

class MavenResourceOverlayStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        project.messageBus.connect()
            .subscribe(ProjectTaskListener.TOPIC, MavenResourceOverlayBuildListener(project))
    }
}
