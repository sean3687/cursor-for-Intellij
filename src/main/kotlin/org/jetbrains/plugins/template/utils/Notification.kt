package org.jetbrains.plugins.template.utils

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.notification.NotificationGroup
import com.intellij.notification.Notification
import com.intellij.openapi.diagnostic.Logger

object NotificationUtil {
    private val LOG = Logger.getInstance(NotificationUtil::class.java)

    private val NOTIFICATION_GROUP: NotificationGroup = NotificationGroupManager.getInstance()
        .getNotificationGroup("Template Plugin Notifications")
        ?: NotificationGroup.balloonGroup("Template Plugin Notifications")

    fun showInfo(project: Project?, content: String, title: String = "Info") {
        show(project, content, title, NotificationType.INFORMATION)
        LOG.info("$title: $content")
    }

    fun showWarning(project: Project?, content: String, title: String = "Warning") {
        show(project, content, title, NotificationType.WARNING)
        LOG.warn("$title: $content")
    }

    fun showError(project: Project?, content: String, title: String = "Error") {
        show(project, content, title, NotificationType.ERROR)
        LOG.error("$title: $content")
    }

    fun debug(project: Project?, content: Any?, title: String = "Debug") {
        val message = when (content) {
            is Map<*, *> -> content.entries.joinToString("\n") { "${it.key}: ${it.value}" }
            is Collection<*> -> content.joinToString("\n")
            else -> content.toString()
        }
        show(project, message, title, NotificationType.INFORMATION)
        LOG.info("$title: $message")
    }

    private fun show(project: Project?, content: String, title: String, type: NotificationType) {
        NOTIFICATION_GROUP.createNotification(title, content, type).notify(project)
    }
}