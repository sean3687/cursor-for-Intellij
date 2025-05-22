package org.jetbrains.plugins.template.startup

import OpenAIClient
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.psi.PsiDocumentManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.util.TextRange
import org.jetbrains.plugins.template.renderer.InlineSuggestionRenderer
import org.jetbrains.plugins.template.utils.NotificationUtil
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class MyProjectActivity4 : ProjectActivity {
    private val LOG = logger<MyProjectActivity4>()
    private val scheduler = Executors.newScheduledThreadPool(1)
    private var debounceTask: ScheduledFuture<*>? = null

    // Get the suggestion service
    private val suggestionService = SuggestionService.getInstance()

    override suspend fun execute(project: Project) {
        try {
            LOG.info("Starting MyProjectActivity execution")

            // Initialize suggestion state for this project
            suggestionService.getState(project)

            // Setup listeners
            setupEditorListeners(project)

            // Schedule an initial suggestion after a short delay
            scheduler.schedule({
                val editor = FileEditorManager.getInstance(project).selectedTextEditor
                if (editor != null) {
//                    NotificationUtil.debug(project, "Plugin initialized, generating initial suggestion")
                    val code = getCurrentFileCode(project)
                    if (code != null) {
                        // Make sure to run on EDT
                        ApplicationManager.getApplication().invokeLater {
                            generateSuggestion(project, editor, code)
                        }
                    }
                }
            }, 3, TimeUnit.SECONDS)

//            NotificationUtil.showInfo(project, "Suggestion System Active", "Code suggestions will appear as you type")
            LOG.info("Suggestion system initialized successfully")
        } catch (e: Exception) {
//            NotificationUtil.showError(project, "Error: ${e.message}", "MyProjectActivity Error")
            LOG.error("Failed to initialize suggestion system", e)
        }
    }

    private fun getCurrentFileCode(project: Project): String? {
        try {
            val editor = FileEditorManager.getInstance(project).selectedTextEditor
            if (editor == null) {
                return null
            }

            val document = editor.document
            val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(document)
            return psiFile?.text
        } catch (e: Exception) {
//            NotificationUtil.showError(project, "Error getting file code: ${e.message}")
            LOG.error("Failed to get current file code", e)
            return null
        }
    }

    fun triggerSuggestion(project: Project) {
        val state = suggestionService.getState(project)

        // Prevent new suggestion if inlay is present
        val editor = FileEditorManager.getInstance(project).selectedTextEditor

        // Cancel previous debounce task if it exists
        debounceTask?.cancel(false)

//        NotificationUtil.debug(project, "Scheduling suggestion with 2-second debounce")

        // Schedule a new task after debounce period
        debounceTask = scheduler.schedule({
            val currentEditor = FileEditorManager.getInstance(project).selectedTextEditor
            if (currentEditor != null && currentEditor == editor) {
                val code = getCurrentFileCode(project)
                if (code != null) {
//                    NotificationUtil.debug(project, "Debounce complete, generating suggestion...")
                    // Ensure we run on EDT by wrapping the call
                    ApplicationManager.getApplication().invokeLater {
                        generateSuggestion(project, currentEditor, code)
                    }
                } else {
//                    NotificationUtil.debug(project, "Failed to get file code after debounce")
                }
            } else {
//                NotificationUtil.debug(project, "Editor changed during debounce period - cancelling")
            }
        }, 2, TimeUnit.SECONDS)
    }

    private fun calculateIndentationLevel(document: com.intellij.openapi.editor.Document, lineNumber: Int, lineText: String): Int {
        // Default indentation is 4 spaces (or user preference)
        val indentUnit = 4

        // Get indentation of current line
        val currentIndent = lineText.takeWhile { it.isWhitespace() }.length

        // Check if the current line ends with characters that would increase indentation
        val shouldIncreaseIndent = lineText.trim().endsWith("{") ||
                lineText.trim().endsWith(":") ||
                lineText.trim().contains("->") ||
                (lineText.contains("if") && !lineText.contains("{")) ||
                (lineText.contains("for") && !lineText.contains("{")) ||
                (lineText.contains("while") && !lineText.contains("{"))

        // Look at previous line for context (if available)
        val prevIndent = if (lineNumber > 0) {
            val prevLineNumber = lineNumber - 1
            val prevLineStartOffset = document.getLineStartOffset(prevLineNumber)
            val prevLineEndOffset = document.getLineEndOffset(prevLineNumber)
            val prevLineText = document.getText(TextRange(prevLineStartOffset, prevLineEndOffset))
            prevLineText.takeWhile { it.isWhitespace() }.length
        } else {
            0
        }

        // Determine appropriate indentation level
        return if (shouldIncreaseIndent) {
            // Increase indentation by one level
            currentIndent + indentUnit
        } else if (currentIndent > prevIndent) {
            // Already at increased indentation
            currentIndent
        } else {
            // Use current indentation
            currentIndent
        }
    }

    private fun processSuggestionIndentation(suggestion: String, baseIndentation: String, indentationLevel: Int): String {
        // Split suggestion into lines
        val lines = suggestion.lines()

        // Keep track of the current indentation level (may change for control structures)
        var currentLevel = indentationLevel

        // Process each line
        return lines.mapIndexed { index, line ->
            if (line.isBlank()) {
                // Keep blank lines as-is
                line
            } else {
                // Adjust indentation level based on content
                if (index > 0) {
                    // Check if previous line might increase indentation
                    val prevLine = lines[index - 1].trim()
                    if (prevLine.endsWith("{") || prevLine.endsWith(":") ||
                        prevLine.contains("->") ||
                        (prevLine.contains("if") && !prevLine.contains("{")) ||
                        (prevLine.contains("for") && !prevLine.contains("{")) ||
                        (prevLine.contains("while") && !prevLine.contains("{"))) {
                        currentLevel += 4
                    }

                    // Check if current line might decrease indentation
                    if (line.trim().startsWith("}") || line.trim().startsWith(")") || line.trim().startsWith("]")) {
                        currentLevel = maxOf(indentationLevel - 4, 0)
                    }
                }

                // Apply calculated indentation to this line
                val indentation = baseIndentation + " ".repeat(maxOf(0, currentLevel - baseIndentation.length))
                "$indentation${line.trim()}"
            }
        }.joinToString("\n")
    }

    private fun generateSuggestion(project: Project, editor: Editor, code: String) {
        val state = suggestionService.getState(project)

        try {
            // Skip if a suggestion is already active
            if (state.active) {
//                NotificationUtil.debug(project, "Skipping suggestion generation - suggestion already active")
                return
            }

            // Get current offset position
            val caretOffset = editor.caretModel.offset

            // Get the context around the caret
            val lineNumber = editor.document.getLineNumber(caretOffset)
            val startLine = maxOf(0, lineNumber - 5) // Get 5 lines before
            val endLine = minOf(editor.document.lineCount - 1, lineNumber + 5) // Get 5 lines after
            
            val context = editor.document.getText(
                TextRange(
                    editor.document.getLineStartOffset(startLine),
                    editor.document.getLineEndOffset(endLine)
                )
            )

            // Get suggestion from OpenAI
            val rawSuggestion = OpenAIClient.getSuggestions(context)

            if (rawSuggestion.isBlank()) {
//                NotificationUtil.debug(project, "No suggestion received from OpenAI")
                return
            }

            // Detect current line's indentation
            val document = editor.document
            //For example, if you're on line 8, this gives you the offset where that line starts (e.g., offset 214).
            val lineStartOffset = document.getLineStartOffset(lineNumber)
            val lineText = document.getText(TextRange(lineStartOffset, caretOffset))
            // You need the actual indentation string for rendering and inserting:
            val currentLineIndentation = lineText.takeWhile { it.isWhitespace() }

            // Calculate indentation based on context
            val indentationLevel = calculateIndentationLevel(document, lineNumber, lineText)

            // Apply proper indentation to the suggestion
            val processedSuggestion = processSuggestionIndentation(rawSuggestion, currentLineIndentation, indentationLevel)

//            NotificationUtil.debug(project, "About to show suggestion: '$processedSuggestion' at offset $caretOffset")

            // Show the suggestion on EDT
            ApplicationManager.getApplication().invokeLater {
                showSuggestion(project, editor, processedSuggestion, caretOffset, currentLineIndentation)
            }

        } catch (e: Exception) {
            LOG.error("Failed to generate or display suggestion", e)
//            NotificationUtil.showError(project, "Error generating suggestion: ${e.message}")
        }
    }

    private fun showSuggestion(project: Project, editor: Editor, suggestion: String, offset: Int, leadingWhitespace: String) {
        if (editor.getUserData(suggestionService.SUGGESTION_INLAY_KEY) != null) {
//            NotificationUtil.debug(project, "Inlay already present, not showing another suggestion")
            return
        }
        try {
//            NotificationUtil.debug(project, "Starting showSuggestion with suggestion: $suggestion")

            // Clear any existing suggestions
            suggestionService.clearSuggestion(editor)

            val state = suggestionService.getState(project)

            // Store state for tracking
            state.active = true
            state.currentEditor = editor
            state.suggestionText = suggestion
            state.startOffset = offset
            state.endOffset = offset + suggestion.length

//            NotificationUtil.debug(project, "SuggestionTextn: $state.suggestionText")
//            NotificationUtil.debug(project, "Insert Offset: $state.startOffset = offset")

            // Create and add the block inlay (between lines) with proper indentation
            val inlay = editor.inlayModel.addBlockElement(
                offset,
                true, // relatesToPrecedingText
                false, // showAbove
                0, // priority
                InlineSuggestionRenderer(suggestion, leadingWhitespace)
            )

            // Store the inlay reference
            editor.putUserData(suggestionService.SUGGESTION_INLAY_KEY, inlay)

//            NotificationUtil.debug(project, "Suggestion inlay added successfully")
        } catch (e: Exception) {
            LOG.error("Failed to show suggestion", e)
//            NotificationUtil.showError(project, "Error showing suggestion: ${e.message}")
        }
    }

    private fun setupEditorListeners(project: Project) {
        try {
            // Add document listeners for all open editors
            EditorFactory.getInstance().eventMulticaster.addDocumentListener(object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) {
                    val state = suggestionService.getState(project)

                    // If a suggestion is active and the document is changed, check if it's from accepting the suggestion
                    if (suggestionService.isAcceptingSuggestion) {
//                        NotificationUtil.debug(project, "Document changed during suggestion acceptance - ignoring")
                        return
                    }
                    if (state.active) {
                        val editor = state.currentEditor
                        if (editor != null && editor.document == event.document) {
                            // Check if the change is at the suggestion's offset
                            if (event.offset != state.startOffset) {
//                                NotificationUtil.debug(project, "Document changed while suggestion active - rejecting suggestion")
                                suggestionService.rejectSuggestion(project)
                            } else {
                                // This change is likely from accepting the suggestion, so don't reject it
//                                NotificationUtil.debug(project, "Document changed at suggestion offset - assuming suggestion acceptance")
                            }
                        }
                    }

//                    NotificationUtil.debug(project, "Document changed - scheduling suggestion")
                    // Trigger a new suggestion after the changes
                    triggerSuggestion(project)
                }
            }, project)

            // Add editor focus listener to detect active editor changes
            FileEditorManager.getInstance(project).addFileEditorManagerListener(
                object : FileEditorManagerListener {
                    override fun selectionChanged(event: FileEditorManagerEvent) {
                        val state = suggestionService.getState(project)

                        // Clear any active suggestion when switching editors
                        if (state.active) {
//                            NotificationUtil.debug(project, "Editor selection changed - rejecting current suggestion")
                            suggestionService.rejectSuggestion(project)
                        }

                        // Trigger suggestion for the newly focused editor
//                        NotificationUtil.debug(project, "Editor selection changed - scheduling new suggestion")
                        triggerSuggestion(project)
                    }
                }
            )

//            NotificationUtil.debug(project, "Editor listeners set up successfully")
        } catch (e: Exception) {
            LOG.error("Failed to setup editor listeners", e)
//            NotificationUtil.showError(project, "Error setting up listeners: ${e.message}")
        }
    }
}