package com.classmemory.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.classmemory.app.i18n.tr
import com.classmemory.app.review.ReviewUnit

@Composable
internal fun ReviewQuickDirectory(
    units: List<ReviewUnit>,
    maxHeight: Dp,
    modifier: Modifier = Modifier,
    onSelect: (String) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    if (units.isEmpty()) return
    if (!expanded) FilledTonalButton(
        onClick = { expanded = true },
        modifier = modifier.width(44.dp).height(64.dp).semantics { contentDescription = tr("展开知识目录", "Open topic directory") },
        contentPadding = PaddingValues(4.dp),
        shape = RoundedCornerShape(topStart = 14.dp, bottomStart = 14.dp),
    ) { Text("≡", style = MaterialTheme.typography.titleLarge) }
    else Surface(modifier.widthIn(max = 250.dp).heightIn(max = maxHeight), shape = RoundedCornerShape(16.dp), shadowElevation = 8.dp, tonalElevation = 6.dp) {
        Column(Modifier.padding(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(tr("知识目录", "Topics"), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = { expanded = false }) { Text(tr("收起", "Hide")) }
            }
            LazyColumn(Modifier.weight(1f, fill = false)) {
                items(units, key = { it.id }) { unit ->
                    TextButton(
                        onClick = { expanded = false; onSelect(unit.id) },
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                    ) { Text(unit.title, Modifier.fillMaxWidth()) }
                }
            }
        }
    }
}
