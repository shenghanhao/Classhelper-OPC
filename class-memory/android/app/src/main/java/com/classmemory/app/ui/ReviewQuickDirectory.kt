package com.classmemory.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.classmemory.app.i18n.tr
import com.classmemory.app.review.ReviewUnit
import com.classmemory.app.ui.icons.ClassMemoryIcons
import com.classmemory.app.ui.theme.ClassMemoryColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReviewQuickDirectory(
    units: List<ReviewUnit>,
    maxHeight: Dp,
    expanded: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onSelect: (String) -> Unit,
    onExercises: (() -> Unit)? = null,
    title: String = "",
    completed: Int = 0,
    total: Int = 0,
    onGuide: (() -> Unit)? = null,
    onPlan: (() -> Unit)? = null,
    onQuestions: (() -> Unit)? = null,
    onGraph: (() -> Unit)? = null,
    onTranscript: (() -> Unit)? = null,
) {
    if (!expanded) return
    val p = ClassMemoryColors.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = p.background) {
        Column(modifier.fillMaxWidth().heightIn(max = maxHeight).padding(horizontal = 22.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("本课目录", "Class index"), Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                SketchAccent(Modifier.size(30.dp))
                IconButton(onClick = onDismiss) { Icon(ClassMemoryIcons.Close, tr("关闭目录", "Close index")) }
            }
            if (title.isNotBlank()) Text(title, color = p.muted, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(18.dp))
            LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(9.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                if (onGuide != null) item { DirectorySectionRow("01", tr("全课导览", "Whole-class guide")) { onDismiss(); onGuide() } }
                if (onPlan != null) item { DirectorySectionRow("02", tr("复习清单", "Review checklist"), "$completed / $total") { onDismiss(); onPlan() } }
                item {
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        ReviewBadge("03")
                        Text(tr("知识讲解", "Knowledge explanations"), Modifier.padding(start = 14.dp), style = MaterialTheme.typography.titleMedium)
                    }
                }
                itemsIndexed(units, key = { _, unit -> "unit:${unit.id}" }) { index, unit ->
                    Surface(onClick = { onDismiss(); onSelect(unit.id) }, color = p.surface,
                        shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, p.border)) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            ReviewBadge("K%02d".format(index + 1))
                            Text(unit.title, Modifier.weight(1f).padding(horizontal = 12.dp), style = MaterialTheme.typography.bodyLarge)
                            Icon(ClassMemoryIcons.ChevronRight, null, Modifier.size(18.dp), tint = p.primary)
                        }
                    }
                }
                if (onQuestions != null) item { DirectorySectionRow("04", tr("全课问答", "Whole-class questions")) { onDismiss(); onQuestions() } }
                if (onExercises != null) item { DirectorySectionRow("05", tr("课后练习", "After-class practice")) { onDismiss(); onExercises() } }
                item { HorizontalDivider(Modifier.padding(vertical = 9.dp), color = p.border) }
                if (onGraph != null) item { DirectorySectionRow("↗", tr("思维导图", "Knowledge map")) { onDismiss(); onGraph() } }
                if (onTranscript != null) item { DirectorySectionRow("↗", tr("课堂转录原文", "Class transcript")) { onDismiss(); onTranscript() } }
            }
        }
    }
}

@Composable
private fun DirectorySectionRow(number: String, title: String, progress: String? = null, onClick: () -> Unit) {
    val p = ClassMemoryColors.current
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth(), color = p.surfaceAlt,
        shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, p.border)) {
        Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(number, style = MaterialTheme.typography.titleMedium, color = p.primary)
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            progress?.let { ReviewBadge(it, p.successBackground, p.success) }
            Icon(ClassMemoryIcons.ChevronRight, null, Modifier.size(18.dp), tint = p.primary)
        }
    }
}
