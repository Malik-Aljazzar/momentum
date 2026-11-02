package com.momentum.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.floor

/** Draws on a grid of [cols] x [rows] square cells, snapped to whole pixels: crisp like the sprites. */
private class PixelGrid(val scope: DrawScope, val cols: Int, val rows: Int) {
    private val cell = floor(minOf(scope.size.width / cols, scope.size.height / rows))
    private val ox = floor((scope.size.width - cell * cols) / 2)
    private val oy = floor((scope.size.height - cell * rows) / 2)
    fun px(x: Int, y: Int, c: Color) = scope.drawRect(c, Offset(ox + x * cell, oy + y * cell), Size(cell, cell))
    fun row(y: Int, x0: Int, x1: Int, c: Color) =
        scope.drawRect(c, Offset(ox + x0 * cell, oy + y * cell), Size(cell * (x1 - x0 + 1), cell))
}

/** A block with stepped corners: outline, fill, a light top edge and a shaded bottom edge. */
private fun PixelGrid.block(fill: Color, outline: Color, light: Color?, shade: Color?) {
    row(0, 1, cols - 2, outline)
    row(rows - 1, 1, cols - 2, outline)
    for (y in 1 until rows - 1) { px(0, y, outline); px(cols - 1, y, outline); row(y, 1, cols - 2, fill) }
    light?.let { row(1, 1, cols - 2, it) }
    shade?.let { row(rows - 2, 1, cols - 2, it) }
}

private val CHECK = listOf(2 to 5, 3 to 6, 4 to 7, 5 to 6, 6 to 5, 7 to 4, 8 to 3)

/** The selection box: an empty pixel frame, or filled yellow with a pixel tick. */
@Composable
fun PixelCheckbox(checked: Boolean, size: Dp = 22.dp) {
    Canvas(Modifier.size(size)) {
        val g = PixelGrid(this, 11, 11)
        if (checked) {
            g.block(Px.Yellow, Color(0xFF8A5A2B), light = Color(0xFFFFE3A8), shade = Color(0xFFE0A84E))
            CHECK.forEach { (x, y) -> g.px(x, y, Color(0xFF1A1C2C)); g.px(x, y + 1, Color(0xFF1A1C2C)) }
        } else {
            g.block(Color(0x14FFFFFF), Color(0x66FFFFFF), light = null, shade = null)
        }
    }
}

/** A dotted pixel line (top to bottom) between two parts of a card. */
@Composable
fun PixelDivider(modifier: Modifier = Modifier, color: Color = Color(0x33FFFFFF)) {
    Canvas(modifier.width(2.dp)) {
        val cell = floor(size.width)
        var y = 0f
        while (y + cell <= size.height) {
            drawRect(color, Offset(0f, y), Size(cell, cell))
            y += cell * 2
        }
    }
}

private val INFO_I = listOf(5 to 2, 5 to 3, 4 to 5, 5 to 5, 5 to 6, 5 to 7, 5 to 8, 4 to 9, 5 to 9, 6 to 9)

/** The info button's face: a pixel frame with an "i" (rule cards: details / edit). */
@Composable
fun PixelInfoIcon(size: Dp = 30.dp) {
    Canvas(Modifier.size(size)) {
        val g = PixelGrid(this, 11, 12)
        g.block(Color(0x14FFFFFF), Color(0x66FFFFFF), light = Color(0x22FFFFFF), shade = null)
        INFO_I.forEach { (x, y) -> g.px(x, y, Color(0xCCF4F4F4)) }
    }
}

/** Up / down button for sorting: a yellow pixel block with a pixel arrow. */
@Composable
fun PixelArrowButton(up: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Canvas(Modifier.size(34.dp, 28.dp).clickable(enabled = enabled, onClick = onClick)) {
        val g = PixelGrid(this, 17, 14)
        val ink = if (enabled) Color(0xFF1A1C2C) else Color(0x55FFFFFF)
        if (enabled) g.block(Px.Yellow, Color(0xFF8A5A2B), light = Color(0xFFFFE3A8), shade = Color(0xFFE0A84E))
        else g.block(Color(0x14FFFFFF), Color(0x33FFFFFF), light = null, shade = null)
        // arrow: tip, widening head, stem
        val shape = listOf(3 to (7..9), 4 to (6..10), 5 to (5..11), 6 to (4..12), 7 to (7..9), 8 to (7..9), 9 to (7..9))
        shape.forEach { (y, xs) -> g.row(if (up) y else 13 - y, xs.first, xs.last, ink) }
    }
}
