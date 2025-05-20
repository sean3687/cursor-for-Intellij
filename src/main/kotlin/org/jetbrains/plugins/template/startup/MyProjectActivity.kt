//package org.jetbrains.plugins.template.startup
//
//import com.intellij.openapi.actionSystem.ActionManager
//import com.intellij.openapi.actionSystem.AnAction
//import com.intellij.openapi.actionSystem.AnActionEvent
//import com.intellij.openapi.command.WriteCommandAction
//import com.intellij.openapi.diagnostic.logger
//import com.intellij.openapi.editor.Editor
//import com.intellij.openapi.editor.EditorFactory
//import com.intellij.openapi.editor.event.DocumentEvent
//import com.intellij.openapi.editor.event.DocumentListener
//import com.intellij.openapi.editor.markup.HighlighterLayer
//import com.intellij.openapi.editor.markup.HighlighterTargetArea
//import com.intellij.openapi.editor.markup.RangeHighlighter
//import com.intellij.openapi.editor.markup.TextAttributes
//import com.intellij.openapi.fileEditor.FileEditorManager
//import com.intellij.openapi.project.Project
//import com.intellij.openapi.startup.ProjectActivity
//import com.intellij.openapi.util.Key
//import com.intellij.psi.PsiDocumentManager
//import com.intellij.codeInsight.lookup.LookupManager
//import com.intellij.codeInsight.lookup.impl.LookupImpl
//import com.intellij.openapi.application.ApplicationManager
//import org.jetbrains.plugins.template.utils.NotificationUtil
//import java.awt.Color
//import java.awt.event.KeyAdapter
//import java.awt.event.KeyEvent
//import java.util.concurrent.Executors
//import java.util.concurrent.ScheduledFuture
//import java.util.concurrent.TimeUnit
//import org.jetbrains.plugins.template.org.jetbrains.plugins.template.OpenAIClient
//import com.intellij.openapi.util.TextRange
//import kotlinx.coroutines.*
//import com.intellij.openapi.fileEditor.FileEditorManagerEvent
//import com.intellij.openapi.fileEditor.FileEditorManagerListener
//
//
//
//class MyProjectActivity : ProjectActivity {
//    private val LOG = logger<MyProjectActivity>()
//    private val scheduler = Executors.newScheduledThreadPool(1)
//    private var debounceTask: ScheduledFuture<*>? = null
//
//    // Use a project-specific map to track suggestion state
//    private val projectSuggestionState = mutableMapOf<Project, SuggestionState>()
//
//    // Keys for editor user data
//    private val SUGGESTION_HIGHLIGHTER_KEY = Key<RangeHighlighter>("SUGGESTION_HIGHLIGHTER_KEY")
//    private val SUGGESTION_TEXT_KEY = Key<String>("SUGGESTION_TEXT_KEY")
//    private val SUGGESTION_START_OFFSET_KEY = Key<Int>("SUGGESTION_START_OFFSET_KEY")
//
//    // UI appearance settings
//    private val SUGGESTION_COLOR = Color(200, 200, 255, 100) // Light blue, semi-transparent
//
//    // Class to track suggestion state for each project
//    private data class SuggestionState(
//        var active: Boolean = false,
//        var currentEditor: Editor? = null,
//        var suggestionText: String = "",
//        var startOffset: Int = 0,
//        var endOffset: Int = 0
//    )
//
//    private data class FileContentInfo(
//        val text: String,
//        val lineIndents: Map<Int, String>, // Map of line number to indentation string
//        val caretLineIndent: String,       // Indentation of the current line
//        val caretOffset: Int               // Current caret position
//    )
//
//    data class FileContentDTO(val caretOffset: Int, val lineIndents: Map<Int, String>)
//
//
//
//    override suspend fun execute(project: Project) {
//        try {
//            LOG.info("Starting MyProjectActivity execution")
//
//            // Initialize suggestion state for this project
//            projectSuggestionState[project] = SuggestionState()
//
//            // Setup listeners
//            setupEditorListeners(project)
//            registerKeyboardShortcuts(project)
//
//            // Schedule an initial suggestion after a short delay
//            scheduler.schedule({
//                val editor = FileEditorManager.getInstance(project).selectedTextEditor
//                if (editor != null) {
//                    NotificationUtil.debug(project, "Plugin initialized, generating initial suggestion")
//                    val fileInfo = getFileContentInfo(project, editor)
//                    if (fileInfo != null) {
//                        // Make sure to run on EDT and launch coroutine for generateSuggestion
//                        ApplicationManager.getApplication().invokeLater {
//                            CoroutineScope(Dispatchers.Main).launch {
//                                generateSuggestion(
//                                    project,
//                                    editor,
//                                    FileContentDTO(fileInfo.caretOffset, fileInfo.lineIndents)
//                                )
//                            }
//                        }
//                    }
//                }
//            }, 3, TimeUnit.SECONDS)
//
//            NotificationUtil.showInfo(project, "Suggestion System Active", "Code suggestions will appear as you type")
//            LOG.info("Suggestion system initialized successfully")
//        } catch (e: Exception) {
//            NotificationUtil.showError(project, "Error: ${e.message}", "MyProjectActivity Error")
//            LOG.error("Failed to initialize suggestion system", e)
//        }
//    }
//
//    private fun getFileContentInfo(project: Project, editor: Editor): FileContentInfo? {
//        try {
//            val document = editor.document
//            val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(document) ?: return null
//
//            val caretOffset = editor.caretModel.offset
//            val caretLine = document.getLineNumber(caretOffset)
//
//            // Build indentation map
//            val lineIndents = mutableMapOf<Int, String>()
//            for (i in 0 until document.lineCount) {
//                val lineStart = document.getLineStartOffset(i)
//                val lineEnd = document.getLineEndOffset(i)
//                val lineText = document.getText(TextRange(lineStart, lineEnd))
//
//                // Extract leading whitespace
//                val indent = lineText.takeWhile { it.isWhitespace() }
//                lineIndents[i] = indent
//            }
//
//            // Get current line indentation
//            val caretLineStart = document.getLineStartOffset(caretLine)
//            val caretLineText = document.getText(TextRange(caretLineStart, document.getLineEndOffset(caretLine)))
//            val caretLineIndent = caretLineText.takeWhile { it.isWhitespace() }
//
//            return FileContentInfo(
//                text = psiFile.text,
//                lineIndents = lineIndents,
//                caretLineIndent = caretLineIndent,
//                caretOffset = caretOffset
//            )
//        } catch (e: Exception) {
//            NotificationUtil.showError(project, "Error getting file code: ${e.message}")
//            LOG.error("Failed to get current file code", e)
//            return null
//        }
//    }
//
//
//
//    private fun triggerSuggestion(project: Project) {
//        val state = projectSuggestionState[project] ?: return
//
//        // Skip if a suggestion is already active
//        if (state.active) {
//            NotificationUtil.debug(project, "Skipping suggestion - already active")
//            return
//        }
//
//        // Cancel previous debounce task if it exists
//        debounceTask?.cancel(false)
//
//        // Get current editor
//        val editor = FileEditorManager.getInstance(project).selectedTextEditor
//        if (editor == null) {
//            NotificationUtil.debug(project, "No active editor found")
//            return
//        }
//
//        NotificationUtil.debug(project, "Scheduling suggestion with 2-second debounce")
//
//        // Schedule a new task after debounce period
//        debounceTask = scheduler.schedule({
//            val currentEditor = FileEditorManager.getInstance(project).selectedTextEditor
//            if (currentEditor != null && currentEditor == editor) {
//                // Get file code and create DTO
//                val fileInfo = getFileContentInfo(project, editor)
//                if (fileInfo != null) {
//                    val fileContentDTO = FileContentDTO(fileInfo.caretOffset, fileInfo.lineIndents)
//                    NotificationUtil.debug(project, "Debounce complete, generating suggestion...")
//
//                    // Ensure we run on EDT by wrapping the call
//                    ApplicationManager.getApplication().invokeLater {
//                        CoroutineScope(Dispatchers.Main).launch {
//                            generateSuggestion(project, currentEditor, fileContentDTO)
//                        }
//                    }
//                } else {
//                    NotificationUtil.debug(project, "Failed to get file code after debounce")
//                }
//            } else {
//                NotificationUtil.debug(project, "Editor changed during debounce period - cancelling")
//            }
//        }, 2, TimeUnit.SECONDS)
//
//    }
//
//    // Inner classes for actions
//    class acceptAction : AnAction() {
//        override fun actionPerformed(e: AnActionEvent) {
//            val project = e.project ?: return
//            val state = projectSuggestionState[project] ?: return
//            if (state.active) {
//                acceptSuggestion(project)
//            }
//        }
//        override fun update(e: AnActionEvent) {
//            val project = e.project ?: return
//            val state = projectSuggestionState[project] ?: return
//            e.presentation.isEnabled = state.active
//        }
//    }
//
//    class rejectAction : AnAction() {
//        override fun actionPerformed(e: AnActionEvent) {
//            val project = e.project ?: return
//            val state = projectSuggestionState[project] ?: return
//            if (state.active) {
//                rejectSuggestion(project)
//            }
//        }
//        override fun update(e: AnActionEvent) {
//            val project = e.project ?: return
//            val state = projectSuggestionState[project] ?: return
//            e.presentation.isEnabled = state.active
//        }
//    }
//
//    suspend fun generateSuggestion(project: Project, editor: Editor, fileInfo: FileContentDTO) {
//        NotificationUtil.debug(project, "Starting generateSuggestion")
//        val state = projectSuggestionState[project] ?: return
//
//        try {
//            if (state.active) {
//                NotificationUtil.debug(project, "Skipping - suggestion already active")
//                return
//            }
//
//            val lineNumber = editor.document.getLineNumber(fileInfo.caretOffset)
//            NotificationUtil.debug(project, "Current line number: $lineNumber")
//
//            val currentIndent = fileInfo.lineIndents[lineNumber] ?: ""
//            NotificationUtil.debug(project, "Current indent: '$currentIndent'")
//
//            // Get the current line text for testing
//            val lineStart = editor.document.getLineStartOffset(lineNumber)
//            val lineEnd = editor.document.getLineEndOffset(lineNumber)
//            val lineText = editor.document.getText(TextRange(lineStart, lineEnd))
//
//            // Test multi-line suggestion
//            val suggestion = when {
//                lineText.contains("return") -> """
//                    // Return the final value
//                    // Additional context
//                    // More details here
//                """.trimIndent()
//                lineText.contains("if") -> """
//                    // Check condition
//                    // Handle true case
//                    // Handle false case
//                """.trimIndent()
//                lineText.contains("for") || lineText.contains("while") -> """
//                    // Process each element
//                    // Update state
//                    // Handle completion
//                """.trimIndent()
//                else -> """
//                    // This is a suggestion based on your code
//                    // Additional context
//                    // More details here
//                """.trimIndent()
//            }
//
//            withContext(Dispatchers.Main) {
//                if (!state.active) {
//                    val formattedSuggestion = formatSuggestion(suggestion, currentIndent)
//                    NotificationUtil.debug(project, "Formatted suggestion: $formattedSuggestion")
//
//                    if (formattedSuggestion.isNotBlank()) {
//                        showSuggestion(project, editor, formattedSuggestion, fileInfo.caretOffset)
//                    } else {
//                        NotificationUtil.debug(project, "Formatted suggestion was empty")
//                    }
//                }
//            }
//        } catch (e: Exception) {
//            LOG.error("Failed to setup suggestion", e)
//            NotificationUtil.showError(project, "Error: ${e.message}")
//        }
//    }
//
//    // Helper method to format suggestions with proper indentation
//    private fun formatSuggestion(rawSuggestion: String, currentIndent: String): String {
//        // Remove any markdown code blocks if present
//        val cleanSuggestion = rawSuggestion.trim()
//            .removePrefix("```kotlin").removePrefix("```java").removePrefix("```").removeSuffix("```")
//            .trim()
//
//        return cleanSuggestion.lines().joinToString("\n") { line ->
//            if (line.isNotBlank()) "$currentIndent$line" else line
//        }
//    }
//
//
//
//
//
//    private fun showSuggestion(project: Project, editor: Editor, suggestion: String, offset: Int) {
//        try {
//            NotificationUtil.debug(project, "Starting showSuggestion with suggestion: $suggestion")
//
//            // Clear any existing suggestions
//            clearSuggestion(editor)
//
//            val state = projectSuggestionState[project] ?: return
//
//            // Store state for tracking
//            state.active = true
//            state.currentEditor = editor
//            state.suggestionText = suggestion
//            state.startOffset = offset
//            state.endOffset = offset + suggestion.length
//
//            // Create grayed-out text attributes
//            val attributes = TextAttributes().apply {
//                foregroundColor = Color(128, 128, 128, 200) // Gray, semi-transparent
//                backgroundColor = SUGGESTION_COLOR
//            }
//
//            // Insert the suggestion text in a grayed-out form
//            ApplicationManager.getApplication().runWriteAction {
//                WriteCommandAction.runWriteCommandAction(project) {
//                    try {
//                        editor.document.insertString(offset, suggestion)
//
//                        // Add the highlighting AFTER inserting text
//                        val markupModel = editor.markupModel
//                        val highlighter = markupModel.addRangeHighlighter(
//                            offset,
//                            offset + suggestion.length,
//                            HighlighterLayer.LAST,
//                            attributes,
//                            HighlighterTargetArea.EXACT_RANGE
//                        )
//
//                        // Store the suggestion text and highlighter
//                        editor.putUserData(SUGGESTION_TEXT_KEY, suggestion)
//                        editor.putUserData(SUGGESTION_HIGHLIGHTER_KEY, highlighter)
//                        editor.putUserData(SUGGESTION_START_OFFSET_KEY, offset)
//
//                        NotificationUtil.debug(project, "Inserted suggestion at offset: $offset with length: ${suggestion.length}")
//                    } catch (e: Exception) {
//                        LOG.error("Failed to insert suggestion", e)
//                        NotificationUtil.showError(project, "Error inserting suggestion: ${e.message}")
//                        state.active = false
//                    }
//                }
//            }
//
//            NotificationUtil.debug(project, "Suggestion displayed successfully")
//        } catch (e: Exception) {
//            LOG.error("Failed to show suggestion", e)
//            NotificationUtil.showError(project, "Error showing suggestion: ${e.message}")
//        }
//    }
//
//    private fun acceptSuggestion(project: Project) {
//        val state = projectSuggestionState[project] ?: return
//        val editor = state.currentEditor ?: return
//
//        if (state.active) {
//            NotificationUtil.debug(project, "Accepting suggestion")
//
//            // For acceptance, we keep the text but remove highlighting
//            val highlighter = editor.getUserData(SUGGESTION_HIGHLIGHTER_KEY)
//
//            // Remove the highlighter
//            if (highlighter != null) {
//                try {
//                    editor.markupModel.removeHighlighter(highlighter)
//                } catch (e: Exception) {
//                    LOG.error("Failed to remove highlighter when accepting", e)
//                }
//            }
//
//            // Clear user data but keep the text
//            editor.putUserData(SUGGESTION_TEXT_KEY, null)
//            editor.putUserData(SUGGESTION_HIGHLIGHTER_KEY, null)
//            editor.putUserData(SUGGESTION_START_OFFSET_KEY, null)
//
//            // Reset state
//            state.active = false
//            state.suggestionText = ""
//            state.startOffset = 0
//            state.endOffset = 0
//
//            NotificationUtil.debug(project, "Suggestion accepted and retained")
//        }
//    }
//
//    private fun rejectSuggestion(project: Project) {
//        val state = projectSuggestionState[project] ?: return
//
//        if (state.active) {
//            // Close the lookup if it's open
//            val editor = state.currentEditor ?: return
//            val lookupManager = LookupManager.getInstance(project)
//            lookupManager.activeLookup?.let { lookup ->
//                if (lookup is LookupImpl) {
//                    lookup.hideLookup(true)
//                }
//            }
//
//            NotificationUtil.debug(project, "Suggestion rejected")
//            state.active = false
//        }
//    }
//
//    private fun clearSuggestion(editor: Editor) {
//        try {
//            val project = editor.project ?: return
//            val state = projectSuggestionState[project] ?: return
//
//            // If we have an active suggestion, remove it
//            if (state.active && state.currentEditor == editor) {
//                val suggestionText = editor.getUserData(SUGGESTION_TEXT_KEY)
//                val startOffset = editor.getUserData(SUGGESTION_START_OFFSET_KEY)
//                val highlighter = editor.getUserData(SUGGESTION_HIGHLIGHTER_KEY)
//
//                if (suggestionText != null && startOffset != null) {
//                    // Remove the text
//                    ApplicationManager.getApplication().runWriteAction {
//                        WriteCommandAction.runWriteCommandAction(project) {
//                            try {
//                                val endOffset = startOffset + suggestionText.length
//                                if (endOffset <= editor.document.textLength) {
//                                    editor.document.deleteString(startOffset, endOffset)
//                                    NotificationUtil.debug(project, "Removed suggestion text from document")
//                                }
//                            } catch (e: Exception) {
//                                LOG.error("Failed to remove suggestion text", e)
//                            }
//                        }
//                    }
//                }
//
//                // Remove the highlighter
//                if (highlighter != null) {
//                    try {
//                        editor.markupModel.removeHighlighter(highlighter)
//                    } catch (e: Exception) {
//                        LOG.error("Failed to remove highlighter", e)
//                    }
//                }
//
//                // Clear user data
//                editor.putUserData(SUGGESTION_TEXT_KEY, null)
//                editor.putUserData(SUGGESTION_HIGHLIGHTER_KEY, null)
//                editor.putUserData(SUGGESTION_START_OFFSET_KEY, null)
//
//                // Reset state
//                state.active = false
//                state.suggestionText = ""
//                state.startOffset = 0
//                state.endOffset = 0
//            }
//        } catch (e: Exception) {
//            LOG.error("Error in clearSuggestion", e)
//        }
//    }
//
//    private fun setupEditorListeners(project: Project) {
//        try {
//            // Add document listeners for all open editors
//            EditorFactory.getInstance().eventMulticaster.addDocumentListener(object : DocumentListener {
//                override fun documentChanged(event: DocumentEvent) {
//                    val state = projectSuggestionState[project] ?: return
//
//                    // If a suggestion is active and the document is changed, cancel it
//                    if (state.active) {
//                        val editor = state.currentEditor
//                        if (editor != null && editor.document == event.document) {
//                            NotificationUtil.debug(project, "Document changed while suggestion active - rejecting suggestion")
//                            rejectSuggestion(project)
//                        }
//                    }
//
//                    NotificationUtil.debug(project, "Document changed - scheduling suggestion")
//                    // Trigger a new suggestion after the changes
//                    triggerSuggestion(project)
//                }
//            }, project)
//
//            // Add editor focus listener to detect active editor changes
//            // Add editor focus listener to detect active editor changes
//            FileEditorManager.getInstance(project).addFileEditorManagerListener(
//                object : FileEditorManagerListener {
//                    override fun selectionChanged(event: FileEditorManagerEvent) {
//                        val state = projectSuggestionState[project] ?: return
//
//                        // Clear any active suggestion when switching editors
//                        if (state.active) {
//                            NotificationUtil.debug(project, "Editor selection changed - rejecting current suggestion")
//                            rejectSuggestion(project)
//                        }
//
//                        // Trigger suggestion for the newly focused editor
//                        NotificationUtil.debug(project, "Editor selection changed - scheduling new suggestion")
//                        triggerSuggestion(project)
//                    }
//                }
//            )
//
//
//            NotificationUtil.debug(project, "Editor listeners set up successfully")
//        } catch (e: Exception) {
//            LOG.error("Failed to setup editor listeners", e)
//            NotificationUtil.showError(project, "Error setting up listeners: ${e.message}")
//        }
//    }
//
//    private fun registerKeyboardShortcuts(project: Project) {
//        val actionManager = ActionManager.getInstance()
//
//        // Action for accepting suggestions (Tab)
//        val acceptAction = object : AnAction() {
//            override fun actionPerformed(e: AnActionEvent) {
//                val state = projectSuggestionState[project] ?: return
//                if (state.active) {
//                    acceptSuggestion(project)
//                }
//            }
//            override fun update(e: AnActionEvent) {
//                val state = projectSuggestionState[project] ?: return
//                e.presentation.isEnabled = state.active
//            }
//        }
//
//        // Action for rejecting suggestions (Escape)
//        val rejectAction = object : AnAction() {
//            override fun actionPerformed(e: AnActionEvent) {
//                val state = projectSuggestionState[project] ?: return
//                if (state.active) {
//                    rejectSuggestion(project)
//                }
//            }
//            override fun update(e: AnActionEvent) {
//                val state = projectSuggestionState[project] ?: return
//                e.presentation.isEnabled = state.active
//            }
//        }
//
//        // Register actions only if not already registered
//        if (actionManager.getAction("AcceptSuggestion") == null) {
//            actionManager.registerAction("AcceptSuggestion", acceptAction)
//        }
//        if (actionManager.getAction("RejectSuggestion") == null) {
//            actionManager.registerAction("RejectSuggestion", rejectAction)
//        }
//
//        // Add key listeners to active editor component
//        val keyAdapter = object : KeyAdapter() {
//            override fun keyPressed(e: KeyEvent) {
//                val state = projectSuggestionState[project] ?: return
//                if (state.active) {
//                    when (e.keyCode) {
//                        KeyEvent.VK_TAB -> {
//                            acceptSuggestion(project)
//                            e.consume()
//                        }
//                        KeyEvent.VK_ESCAPE -> {
//                            rejectSuggestion(project)
//                            e.consume()
//                        }
//                        KeyEvent.VK_CAPS_LOCK -> {
//                            acceptSuggestion(project)
//                            e.consume()
//                        }
//                    }
//                }
//            }
//        }
//
//        EditorFactory.getInstance().addEditorFactoryListener(
//            object : com.intellij.openapi.editor.event.EditorFactoryListener {
//                override fun editorCreated(event: com.intellij.openapi.editor.event.EditorFactoryEvent) {
//                    event.editor.contentComponent.addKeyListener(keyAdapter)
//                }
//                override fun editorReleased(event: com.intellij.openapi.editor.event.EditorFactoryEvent) {
//                    event.editor.contentComponent.removeKeyListener(keyAdapter)
//                }
//            },
//            project
//        )
//
//        NotificationUtil.debug(project, "Keyboard shortcuts registered")
//    }
//}