package ro.gs1s.mvnresfilter

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project

object OverlayNotifications {

    private const val GROUP_ID = "Maven Resource Overlay"

    fun notifySuccess(project: Project, message: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP_ID)
            .createNotification(message, NotificationType.INFORMATION)
            .notify(project)
    }

    fun notifyWarning(project: Project, message: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP_ID)
            .createNotification(message, NotificationType.WARNING)
            .notify(project)
    }

    fun notifyError(project: Project, message: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP_ID)
            .createNotification(message, NotificationType.ERROR)
            .notify(project)
    }
}
