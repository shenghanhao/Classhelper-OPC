package com.classmemory.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.classmemory.app.data.Session
import com.classmemory.app.data.displayCourseType
import com.classmemory.app.i18n.tr
import com.classmemory.app.repository.SessionRepository
import com.classmemory.app.review.ReviewRun
import com.classmemory.app.review.ReviewRuntime
import com.classmemory.app.ui.icons.ClassMemoryIcons
import com.classmemory.app.ui.theme.ClassMemoryColors
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
        catch (_: Exception) { error = tr("复习列表读取失败，请在课堂详情中重试", "Couldn't load review tasks. Open class details to retry.") }
    }
    val palette = ClassMemoryColors.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val next = pending.firstOrNull()
        if (next == null) HomeWelcomeCard() else {
            val plan = next.plan!!
            val classroom = sessions.firstOrNull { it.id == next.input.sessionId }
            Surface(color = palette.surfaceAlt, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Surface(color = palette.primary.copy(alpha = .10f), shape = RoundedCornerShape(8.dp)) {
                            Text(tr("继续学习", "Keep learning"), Modifier.padding(horizontal = 9.dp, vertical = 5.dp), style = MaterialTheme.typography.labelMedium, color = palette.primary)
                        }
                        Text(tr("已完成 ${next.completedStepIndices.size} / ${plan.steps.size} 项", "${next.completedStepIndices.size} / ${plan.steps.size} completed"),
                            style = MaterialTheme.typography.bodySmall, color = palette.muted, modifier = Modifier.weight(1f))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(classroom?.title?.takeIf { it.isNotBlank() } ?: plan.title, style = MaterialTheme.typography.headlineSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            Text(classroom?.courseType?.let(::displayCourseType) ?: tr("课堂复习", "Class review"), color = palette.muted, style = MaterialTheme.typography.bodyMedium)
                        }
                        NotebookIllustration(Modifier.size(72.dp))
                    }
                    LinearProgressIndicator(progress = { if (plan.steps.isEmpty()) 0f else next.completedStepIndices.size.toFloat() / plan.steps.size },
                        modifier = Modifier.fillMaxWidth().height(5.dp), trackColor = palette.primary.copy(alpha = .12f))
                    Button(onClick = { onOpen(next.input.sessionId) }, shape = RoundedCornerShape(13.dp)) {
                        Text(tr("继续复习  →", "Continue review  →"))
                    }
                }
            }
        }
        error?.let { Text(it, color = palette.error, style = MaterialTheme.typography.bodyMedium) }
        if (pending.isNotEmpty()) {
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Text(tr("待复习", "To review"), style = MaterialTheme.typography.titleLarge)
                Surface(color = palette.surfaceAlt, shape = RoundedCornerShape(8.dp)) { Text(pending.size.toString(), Modifier.padding(horizontal = 9.dp, vertical = 3.dp), color = palette.primary, style = MaterialTheme.typography.labelMedium) }
            }
            pending.forEach { run ->
                OutlinedCard(onClick = { onOpen(run.input.sessionId) }, modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.outlinedCardColors(containerColor = palette.surface), border = BorderStroke(1.dp, palette.border), shape = RoundedCornerShape(13.dp)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.size(40.dp).background(palette.surfaceAlt, RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) {
                            Icon(ClassMemoryIcons.Notebook, null, tint = palette.primary)
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(sessions.firstOrNull { it.id == run.input.sessionId }?.title ?: run.plan!!.title,
                                style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(tr("建议 ${run.plan!!.suggestedDate}", "Suggested ${run.plan!!.suggestedDate}"), style = MaterialTheme.typography.bodySmall, color = palette.muted)
                        }
                        Text("${run.completedStepIndices.size}/${run.plan!!.steps.size}", color = palette.muted, style = MaterialTheme.typography.bodyMedium)
                        Text("›", style = MaterialTheme.typography.titleLarge, color = palette.muted)
                    }
                }
            }
        }
    }
}

@Composable
internal fun HomeWelcomeCard() {
    val palette = ClassMemoryColors.current
    Surface(color = palette.surfaceAlt, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text(tr("课堂记忆", "Class Memory"), color = palette.primary, style = MaterialTheme.typography.labelMedium)
                Text(tr("专心听课，\n留下线索。", "Stay present.\nKeep the ideas."), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(tr("本地转写 · 随记疑问 · 课后复习", "Local transcription · Questions · Review"), style = MaterialTheme.typography.bodySmall, color = palette.muted)
            }
            NotebookIllustration(Modifier.size(108.dp))
        }
    }
}
