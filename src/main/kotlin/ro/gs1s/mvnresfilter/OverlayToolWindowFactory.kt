package ro.gs1s.mvnresfilter

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import java.awt.BorderLayout
import java.awt.Font
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.SwingUtilities

class OverlayToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val textArea = JTextArea().apply {
            isEditable = false
            font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        }

        val overlayLog = OverlayLog.getInstance(project)

        // Load existing lines
        for (line in overlayLog.getLines()) {
            textArea.append(line + "\n")
        }

        // Listen for new lines
        val listener: (String) -> Unit = { line ->
            SwingUtilities.invokeLater {
                textArea.append(line + "\n")
                textArea.caretPosition = textArea.document.length
            }
        }
        overlayLog.addListener(listener)

        val clearButton = JButton("Clear").apply {
            addActionListener {
                overlayLog.clear()
                textArea.text = ""
            }
        }

        val panel = JPanel(BorderLayout()).apply {
            add(JBScrollPane(textArea), BorderLayout.CENTER)
            add(clearButton, BorderLayout.SOUTH)
        }

        val content = ContentFactory.getInstance().createContent(panel, null, false)
        toolWindow.contentManager.addContent(content)
    }
}
