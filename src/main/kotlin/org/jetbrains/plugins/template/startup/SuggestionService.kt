package org.jetbrains.plugins.template.startup

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiDocumentManager
import org.jetbrains.plugins.template.utils.NotificationUtil

class SuggestionService {
    // Use a project-specific map to track suggestion state
    private val projectSuggestionState = mutableMapOf<Project, SuggestionState>()
    
    // Add this with your other Key declarations
    val SUGGESTION_INLAY_KEY = Key<Inlay<*>>("SUGGESTION_INLAY_KEY")

    // Flag to suppress document listener during suggestion acceptance
    var isAcceptingSuggestion: Boolean = false

    // Keys for editor user data
    private val SUGGESTION_HIGHLIGHTER_KEY = Key<RangeHighlighter>("SUGGESTION_HIGHLIGHTER_KEY")
    private val SUGGESTION_TEXT_KEY = Key<String>("SUGGESTION_TEXT_KEY")
    private val SUGGESTION_START_OFFSET_KEY = Key<Int>("SUGGESTION_START_OFFSET_KEY")

    // Class to track suggestion state for each project
    data class SuggestionState(
        var active: Boolean = false,
        var currentEditor: Editor? = null,
        var suggestionText: String = "",
        var startOffset: Int = 0,
        var endOffset: Int = 0
    )

    companion object {
        fun getInstance(): SuggestionService {
            return ApplicationManager.getApplication().getService(SuggestionService::class.java)
        }
    }

    class acceptAction : AnAction() {
        override fun actionPerformed(e: AnActionEvent) {
            val project = e.project ?: return
            val service = getInstance()
            val state = service.projectSuggestionState[project] ?: return
            NotificationUtil.debug(project, "acceptAction.actionPerformed called. state.active=${state.active}, suggestionText='${state.suggestionText}', insertOffset=${state.startOffset}")
            if (state.active) {
                service.acceptSuggestion(project)
                org.jetbrains.plugins.template.startup.MyProjectActivity4().triggerSuggestion(project)
            }
        }
        override fun update(e: AnActionEvent) {
            val project = e.project ?: return
            val service = getInstance()
            val state = service.projectSuggestionState[project] ?: return
            e.presentation.isEnabled = state.active
        }
        override fun getActionUpdateThread(): ActionUpdateThread {
            return ActionUpdateThread.EDT
        }
    }

    class rejectAction : AnAction() {
        override fun actionPerformed(e: AnActionEvent) {
            val project = e.project ?: return
            val service = getInstance()
            val state = service.projectSuggestionState[project] ?: return
            NotificationUtil.debug(project, "rejectAction.actionPerformed called. state.active=${state.active}, suggestionText='${state.suggestionText}', insertOffset=${state.startOffset}")
            if (state.active) {
                service.rejectSuggestion(project)
                org.jetbrains.plugins.template.startup.MyProjectActivity4().triggerSuggestion(project)
            }
        }
        override fun update(e: AnActionEvent) {
            val project = e.project ?: return
            val service = getInstance()
            val state = service.projectSuggestionState[project] ?: return
            e.presentation.isEnabled = state.active
        }
        override fun getActionUpdateThread(): ActionUpdateThread {
            return ActionUpdateThread.EDT
        }
    }

    fun getState(project: Project): SuggestionState {
        return projectSuggestionState.getOrPut(project) { SuggestionState() }
    }

    fun acceptSuggestion(project: Project) {
        val state = projectSuggestionState[project] ?: return
        val editor = state.currentEditor ?: return

        NotificationUtil.debug(project, "acceptSuggestion called. state.active=${state.active}, suggestionText='${state.suggestionText}', insertOffset=${state.startOffset}")

        if (state.active) {
            NotificationUtil.debug(project, "Accepting suggestion (setting isAcceptingSuggestion = true)")

            val suggestionText = state.suggestionText
            val document = editor.document
            val currentLine = document.getLineNumber(state.startOffset)
            val nextLine = currentLine + 1
            val insertOffset = if (nextLine < document.lineCount) {
                document.getLineStartOffset(nextLine)
            } else {
                document.textLength
            }

            isAcceptingSuggestion = true
            ApplicationManager.getApplication().invokeLater {
                NotificationUtil.debug(project, "Inside invokeLater for suggestion acceptance")
                com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
                    try {
                        NotificationUtil.debug(project, "Inside runWriteAction for suggestion acceptance")
                        editor.document.insertString(insertOffset, suggestionText)
                        PsiDocumentManager.getInstance(project).commitDocument(editor.document)
                        editor.caretModel.moveToOffset(insertOffset + suggestionText.length)
                        editor.scrollingModel.scrollToCaret(com.intellij.openapi.editor.ScrollType.CENTER)
                        editor.getUserData(SUGGESTION_INLAY_KEY)?.dispose()
                        editor.putUserData(SUGGESTION_INLAY_KEY, null)
                        NotificationUtil.debug(project, "Suggestion inserted and inlay disposed")
                    } catch (e: Exception) {
                        NotificationUtil.showError(project, "Exception during suggestion acceptance: ${e.message}")
                        e.printStackTrace()
                    } finally {
                        isAcceptingSuggestion = false
                        NotificationUtil.debug(project, "Reset isAcceptingSuggestion = false after insertion")
                    }
                }
                clearSuggestion(editor)
                NotificationUtil.debug(project, "Suggestion accepted and inserted")
            }
        }
    }

    fun rejectSuggestion(project: Project) {
        val state = projectSuggestionState[project] ?: return

        if (state.active) {
            val editor = state.currentEditor ?: return

            // Dispose of the inlay immediately
            editor.getUserData(SUGGESTION_INLAY_KEY)?.let { inlay ->
                ApplicationManager.getApplication().invokeLater {
                    inlay.dispose()
                }
                editor.putUserData(SUGGESTION_INLAY_KEY, null)
            }

            // Clear state
            state.active = false
            state.suggestionText = ""
            state.startOffset = 0
            state.endOffset = 0

            NotificationUtil.debug(project, "Suggestion rejected and inlay disposed")
        }
    }

    fun clearSuggestion(editor: Editor) {
        try {
            val project = editor.project ?: return
            val state = projectSuggestionState[project] ?: return

            if (state.active && state.currentEditor == editor) {
                // Dispose of the inlay if it exists
                editor.getUserData(SUGGESTION_INLAY_KEY)?.let { inlay ->
                    ApplicationManager.getApplication().invokeLater {
                        inlay.dispose()
                    }
                }

                // Clear user data
                editor.putUserData(SUGGESTION_INLAY_KEY, null)
                editor.putUserData(SUGGESTION_TEXT_KEY, null)
                editor.putUserData(SUGGESTION_START_OFFSET_KEY, null)

                // Reset state
                state.active = false
                state.suggestionText = ""
                state.startOffset = 0
                state.endOffset = 0
            }
        } catch (e: Exception) {
            // Log error
        }
    }
} 