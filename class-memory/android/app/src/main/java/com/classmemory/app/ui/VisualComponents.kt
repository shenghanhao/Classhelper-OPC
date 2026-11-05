package com.classmemory.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.classmemory.app.ui.theme.ClassMemoryColors

/** Original vector decoration: no subject-specific data, text, or synthetic audio levels. */
@Composable
fun NotebookIllustration(modifier: Modifier = Modifier) {
    val palette = ClassMemoryColors.current
    Canvas(modifier.size(112.dp).clearAndSetSemantics { }) {
        scale(size.width / 120f, size.height / 120f, pivot = Offset.Zero) {
            rotate(7f, Offset(60f, 60f)) {
                val cover = Path().apply {
                    moveTo(13f, 25f); quadraticTo(38f, 18f, 59f, 30f)
                    quadraticTo(85f, 20f, 108f, 27f); lineTo(105f, 102f)
                    quadraticTo(80f, 94f, 59f, 101f); quadraticTo(35f, 92f, 10f, 98f); close()
                }
                drawPath(cover, palette.primary.copy(alpha = .18f))
                val paper = Path().apply {
                    moveTo(15f, 19f); quadraticTo(40f, 14f, 60f, 25f)
                    quadraticTo(84f, 16f, 108f, 22f); lineTo(105f, 94f)
                    quadraticTo(82f, 87f, 60f, 95f); quadraticTo(37f, 86f, 12f, 91f); close()
                }
                drawPath(paper, Color(0xFFFFFBF0))
                drawPath(paper, Color(0xFFB8ABDF), style = Stroke(1.4f))
                drawLine(Color(0xFFDCCEB8), Offset(60f, 26f), Offset(60f, 92f), 1.4f)
                repeat(5) { line ->
                    val y = 36f + line * 10
                    drawLine(Color(0xFFE1D8C7), Offset(24f, y), Offset(48f, y + 2f), 2f, StrokeCap.Round)
                    drawLine(Color(0xFFE1D8C7), Offset(71f, y + 1f), Offset(95f, y), 2f, StrokeCap.Round)
                }
                val ribbon = Path().apply {
                    moveTo(76f, 19f); lineTo(88f, 19f); lineTo(87f, 42f)
                    lineTo(81f, 37f); lineTo(75f, 41f); close()
                }
                drawPath(ribbon, Color(0xFF70C6B4))
                rotate(19f, Offset(102f, 77f)) {
                    drawRoundRect(Color(0xFF4E36E9), Offset(99f, 51f), Size(8f, 51f), androidx.compose.ui.geometry.CornerRadius(3f))
                    drawLine(Color(0xFFA89AFF), Offset(102f, 57f), Offset(102f, 94f), 2f)
                    val tip = Path().apply { moveTo(99f, 102f); lineTo(103f, 111f); lineTo(107f, 102f); close() }
                    drawPath(tip, Color(0xFFBFA885))
                }
            }
            drawLine(palette.primary, Offset(13f, 9f), Offset(8f, 2f), 2f, StrokeCap.Round)
            drawLine(palette.primary, Offset(22f, 6f), Offset(22f, 0f), 2f, StrokeCap.Round)
            drawLine(palette.primary, Offset(5f, 17f), Offset(0f, 15f), 2f, StrokeCap.Round)
        }
    }
}

@Composable
fun SketchAccent(modifier: Modifier = Modifier) {
    val color = ClassMemoryColors.current.primary
    Canvas(modifier.size(24.dp).clearAndSetSemantics { }) {
        drawLine(color, Offset(size.width * .2f, size.height * .7f), Offset(0f, size.height * .2f), 2.dp.toPx(), StrokeCap.Round)
        drawLine(color, Offset(size.width * .5f, size.height * .55f), Offset(size.width * .65f, 0f), 2.dp.toPx(), StrokeCap.Round)
        drawLine(color, Offset(size.width * .75f, size.height * .8f), Offset(size.width, size.height * .5f), 2.dp.toPx(), StrokeCap.Round)
    }
}
