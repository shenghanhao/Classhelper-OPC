package com.classmemory.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.classmemory.app.data.Session
import com.classmemory.app.i18n.tr
import com.classmemory.app.repository.SessionRepository
import com.classmemory.app.review.ReviewRun
import com.classmemory.app.review.ReviewRuntime
import kotlinx.coroutines.CancellationException

internal fun pendingReviews(runs: List<ReviewRun>, sessionIds: Set<String>) = runs
    .filter { it.plan != null && it.reviewedAt == null && it.input.sessionId in sessionIds }
    .sortedBy { it.plan!!.suggestedDate }

@Composable
fun PendingReviews(repo: SessionRepository, runtime: ReviewRuntime, sessions: List<Session>, onOpen: (String) -> Unit) {
    val changes by runtime.store.changes.collectAsState()
    var pending by remember { mutableStateOf<List<ReviewRun>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(changes, sessions.map { it.id }) {
        try {
            pending = pendingReviews(runtime.store.allLatest(), sessions.map { it.id }.toSet())
            error = null
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = tr("复习列表读取失败，请在课堂详情中重试", "Couldn't load review tasks. Open class details to retry.") }
    }
    if (pending.isNotEmpty() || error != null) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(tr("待复习", "To review"), style = MaterialTheme.typography.titleLarge)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        pending.forEach { run ->
            OutlinedCard(onClick = { onOpen(run.input.sessionId) }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(run.plan!!.title, style = MaterialTheme.typography.titleMedium)
                    Text(tr("建议复习：${run.plan.suggestedDate}", "Suggested: ${run.plan.suggestedDate}"))
                    Text("${run.completedStepIndices.size}/${run.plan.steps.size}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
