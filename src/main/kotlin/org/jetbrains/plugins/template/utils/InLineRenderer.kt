package org.jetbrains.plugins.template.renderer

import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.markup.TextAttributes
import java.awt.Color
import java.awt.Font
import java.awt.Graphics
import java.awt.Rectangle

class InlineSuggestionRenderer(
    private val suggestion: String,
    private val indent: String = ""
) : EditorCustomElementRenderer {

//   Before
//   private val lines: List<String> = suggestion.lines().map { line ->
//        if (line.isBlank()) line else "$indent$line"

//   }
    //Fix
    private val lines: List<String> = suggestion.lines()
    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        val editor = inlay.editor
        val fontMetrics = editor.contentComponent.getFontMetrics(editor.colorsScheme.getFont(EditorFontType.PLAIN))
        // Return width of the longest line
        return lines.maxOf { line -> fontMetrics.stringWidth(line) }
    }

    override fun paint(
        inlay: Inlay<*>,
        g: Graphics,
        targetRegion: Rectangle,
        textAttributes: TextAttributes
    ) {
        val editor = inlay.editor
        val font = editor.colorsScheme.getFont(EditorFontType.PLAIN).deriveFont(Font.ITALIC)
        g.font = font
        g.color = Color(150, 150, 150, 180) // ghost gray

        val metrics = g.fontMetrics
        val lineHeight = metrics.height
        var y = targetRegion.y + metrics.ascent

        // Draw each line
        lines.forEachIndexed { index, line ->
            // Calculate x position for each line (for proper indentation)
            val x = targetRegion.x + if (index > 0) {
                metrics.stringWidth(indent)
            } else {
                0
            }

            g.drawString(line, x, y)
            y += lineHeight
        }
    }

    override fun calcHeightInPixels(inlay: Inlay<*>): Int {
        val editor = inlay.editor
        val lineHeight = editor.lineHeight
        return lines.size * lineHeight
    }

    override fun toString(): String = "InlineSuggestionRenderer(lines=${lines.size})"
}