package com.classmemory.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.classmemory.app.i18n.tr
import com.classmemory.app.review.ReviewRun
import com.classmemory.app.ui.icons.ClassMemoryIcons
import com.classmemory.app.ui.theme.ClassMemoryColors

@Composable
internal fun ReviewPaper(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val p = ClassMemoryColors.current
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = p.surface,
        border = BorderStroke(1.dp, p.border)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
internal fun ReviewSectionHeading(title: String, modifier: Modifier = Modifier, number: String? = null) {
    val p = ClassMemoryColors.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (number != null) ReviewBadge(number, p.surfaceAlt, p.primary)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(4.dp).height(22.dp).background(p.primary, RoundedCornerShape(3.dp)))
            Text(title, Modifier.weight(1f).padding(start = 10.dp), style = MaterialTheme.typography.titleLarge, color = p.text)
            SketchAccent(Modifier.size(32.dp))
        }
    }
}

@Composable
internal fun ReviewBadge(text: String, background: Color = ClassMemoryColors.current.surfaceAlt, foreground: Color = ClassMemoryColors.current.primary) {
    Surface(color = background, shape = RoundedCornerShape(100.dp)) {
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 4.dp), color = foreground,
            style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
internal fun ReviewShortcut(title: String, detail: String, icon: ImageVector, modifier: Modifier = Modifier, tone: Int = 0, onClick: () -> Unit) {
    val p = ClassMemoryColors.current
    val bg = when (tone) { 1 -> p.successBackground; 2 -> p.warningBackground; else -> p.surfaceAlt }
    val tint = when (tone) { 1 -> p.success; 2 -> p.warning; else -> p.primary }
    Surface(onClick = onClick, modifier = modifier, color = bg, shape = RoundedCornerShape(14.dp)) {
        Row(Modifier.padding(12.dp).heightIn(min = 56.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(25.dp), tint = tint)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = p.text)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = p.muted)
            }
        }
    }
}

@Composable
internal fun ReviewOrganizingHero(run: ReviewRun?, stage: String, title: String, course: String?, onCancel: () -> Unit) {
    val p = ClassMemoryColors.current
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Surface(shape = RoundedCornerShape(22.dp), color = p.surfaceAlt) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(tr("正在整理知识讲解", "Organizing your class"), style = MaterialTheme.typography.headlineSmall)
                Text(listOfNotNull(course, title).distinct().joinToString(" · "), color = p.muted)
                Text(stage, color = p.primary, style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    listOf(ClassMemoryIcons.Notebook, ClassMemoryIcons.File, ClassMemoryIcons.Edit, ClassMemoryIcons.Photo).forEachIndexed { i, icon ->
                        Surface(Modifier.padding(vertical = 12.dp).size(48.dp).graphicsLayer { rotationZ = if (i % 2 == 0) -9f else 8f },
                            shape = RoundedCornerShape(12.dp), color = p.surface, shadowElevation = 3.dp) {
                            Box(contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(27.dp), tint = p.primary) }
                        }
                    }
                }
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = p.primary, trackColor = p.border)
            }
        }
        ReviewStageRow(tr("全课导览", "Whole-class guide"), run?.outline != null)
        ReviewStageRow(tr("知识讲解 · ${run?.units?.size ?: 0} / ${run?.outline?.topics?.size ?: 0}", "Knowledge units · ${run?.units?.size ?: 0} / ${run?.outline?.topics?.size ?: 0}"), run?.outline != null && run.units.size == run.outline.topics.size)
        ReviewStageRow(tr("生成复习清单", "Build the review checklist"), run?.plan != null)
        Text(tr("已完成的内容会保留", "Completed material is saved"), color = p.muted, style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text(tr("取消整理", "Cancel organizing")) }
    }
}

@Composable
private fun ReviewStageRow(label: String, done: Boolean) {
    val p = ClassMemoryColors.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(Modifier.size(23.dp), color = if (done) p.successBackground else p.surfaceAlt, shape = RoundedCornerShape(100.dp)) {
            Box(contentAlignment = Alignment.Center) { Text(if (done) "✓" else "○", color = if (done) p.success else p.primary) }
        }
        Text(label, style = MaterialTheme.typography.titleSmall)
    }
}
