package com.classmemory.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.classmemory.app.i18n.tr
import com.classmemory.app.review.ReviewUnit

@Composable
internal fun ReviewQuickDirectory(
    units: List<ReviewUnit>,
    maxHeight: Dp,
    expanded: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onSelect: (String) -> Unit,
    onExercises: (() -> Unit)? = null,
) {
    if (!expanded) return
    val density = LocalDensity.current
    Popup(alignment = Alignment.BottomEnd, offset = with(density) { IntOffset(-16.dp.roundToPx(), -208.dp.roundToPx()) },
        onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
    Surface(modifier.widthIn(max = 280.dp).heightIn(max = maxHeight), shape = RoundedCornerShape(16.dp), shadowElevation = 8.dp, tonalElevation = 6.dp) {
        Column(Modifier.padding(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(tr("目录", "Index"), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = onDismiss) { Text(tr("收起", "Hide")) }
            }
            LazyColumn(Modifier.weight(1f, fill = false)) {
                items(units, key = { "unit:${it.id}" }) { unit ->
                    TextButton(
                        onClick = { onDismiss(); onSelect(unit.id) },
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                    ) { Text(unit.title, Modifier.fillMaxWidth()) }
                }
                if (onExercises != null) item(key = "navigation:exercises") {
                    TextButton(onClick = { onDismiss(); onExercises() }, modifier = Modifier.fillMaxWidth()) {
                        Text(tr("课后练习", "After-class practice"), Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
    }
}
