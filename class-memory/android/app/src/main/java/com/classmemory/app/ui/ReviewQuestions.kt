package com.classmemory.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.TransformOrigin
import kotlinx.coroutines.Job
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.classmemory.app.i18n.tr
import com.classmemory.app.review.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun ReviewQuestions(threads: List<ReviewQaThread>, wholeClass: Boolean = false) {
    if (threads.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    Column {
        TextButton(onClick = { expanded = !expanded }) { Text(if (wholeClass) tr("全课问答", "Whole-class questions") else tr("展开 / 收起问答", "Show / hide questions")) }
        if (expanded) threads.forEach { thread -> thread.turns.forEach { turn ->
            Text(tr("问：", "Q: ") + turn.question, style = MaterialTheme.typography.titleSmall)
            if (turn.status == "answered") ReviewContent(turn.answerMarkdown) else Text(if (turn.status == "pending") tr("待处理", "Pending") else tr("处理失败", "Failed"))
            if (turn.clarificationNeeded) Text(tr("需要进一步澄清", "Further clarification needed"))
        } }
    }
}

@Composable
internal fun ReviewQuestionDialog(run: ReviewRun, connection: ReviewConnection, runtime: ReviewRuntime, enabled: Boolean, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var target by remember { mutableStateOf<String?>(null) }
    var threadId by remember { mutableStateOf<String?>(null) }
    var question by remember { mutableStateOf("") }
    var retryId by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var pending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf(false) }
    var localRun by remember(run) { mutableStateOf(run) }
    val units = run.outline?.topics?.mapNotNull { run.units[it.id] } ?: run.units.values.toList()
    Dialog(onDismissRequest = { if (!pending) onClose() }) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxWidth().heightIn(max = 600.dp).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("课堂问答", "Class questions"), style = MaterialTheme.typography.titleLarge)
                Text(tr("选择提问范围", "Choose a scope"))
                Row(Modifier.fillMaxWidth()) {
                    TextButton(onClick = { target = null; threadId = null }) { Text(if (target == null) "✓ " + tr("全课", "Whole class") else tr("全课", "Whole class")) }
                }
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    units.forEach { unit -> TextButton(onClick = { target = unit.id; threadId = null }) { Text((if (target == unit.id) "✓ " else "") + unit.title) } }
                    localRun.qaThreads.filter { it.targetUnitId == target }.forEach { thread ->
                        thread.turns.forEach { turn ->
                            Text(tr("问：", "Q: ") + turn.question)
                            if (turn.status == "answered") ReviewContent(turn.answerMarkdown) else {
                                Text(if (turn.status == "pending") tr("待处理", "Pending") else tr("处理失败", "Failed"))
                                TextButton(enabled = !pending, onClick = { threadId = thread.id; retryId = turn.id; question = turn.question; error = null }) { Text(tr("重试此问题", "Retry this question")) }
                            }
                        }
                        TextButton(onClick = { threadId = thread.id }) { Text(if (threadId == thread.id) tr("已选择追问", "Follow-up selected") else tr("追问此对话", "Follow up")) }
                    }
                }
                OutlinedTextField(value = question, onValueChange = { question = it; retryId = null; error = null }, label = { Text(tr("输入问题", "Your question")) }, modifier = Modifier.fillMaxWidth())
                error?.let { Text(it + "\n" + tr("请在上方选择该问题的“重试此问题”；若未保存，可修改问题后重新提问。", "Select Retry this question above, or edit and ask again if it was not saved."), color = MaterialTheme.colorScheme.error) }
                if (!enabled) Text(tr("资料已过期，请先更新。", "Material is outdated; update it first."))
                if (pending) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(tr("正在处理", "Processing")) }
                Row { TextButton(onClick = { job?.cancel(); onClose() }) { Text(if (pending) tr("取消并关闭", "Cancel and close") else tr("关闭", "Close")) }; Button(onClick = { confirm = true }, enabled = enabled && connection.isConfigured() && question.isNotBlank() && !pending && (error == null || retryId != null)) { Text(if (retryId != null) tr("重试", "Retry") else tr("提问", "Ask")) } }
            }
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text(tr("确认发送", "Confirm sending")) }, text = { Text(tr("将向配置的服务发送课堂文字、课程上下文、疑问和注释、图片时间索引、已生成资料、此问题及同一对话历史；不会发送音频、图片像素或图片路径。", "The configured service receives classroom text, course context, questions and notes, photo time indexes, generated material, this question and conversation history. Audio, image pixels and paths are not sent.")) }, confirmButton = { TextButton(onClick = {
        confirm = false; pending = true; error = null
        job = scope.launch { try { localRun = runtime.ask(localRun, connection, question, target, threadId, retryId); question = ""; retryId = null } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message ?: tr("提问失败", "Question failed") } finally { pending = false } }
    }) { Text(tr("发送", "Send")) } }, dismissButton = { TextButton(onClick = { confirm = false }) { Text(tr("取消", "Cancel")) } })
}

@Composable
internal fun ReviewGraphSection(run: ReviewRun, connection: ReviewConnection, runtime: ReviewRuntime, enabled: Boolean, onNode: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var localRun by remember(run) { mutableStateOf(run) }
    Text(tr("知识结构图", "Knowledge map"), style = MaterialTheme.typography.titleMedium)
    localRun.graph?.let { graph ->
        var scale by remember { mutableFloatStateOf(1f) }
        var pan by remember { mutableStateOf(Offset.Zero) }
        val nodes = graph.nodes.filter { localRun.units.containsKey(it) }
        Text(tr("双指缩放、拖动；点击知识点跳转", "Pinch to zoom, drag, and tap a topic to jump"))
        val layout = remember(nodes, graph.edges) { graphLayout(nodes, graph.edges) }
        Box(Modifier.fillMaxWidth().height(300.dp).clipToBounds().pointerInput(Unit) { detectTransformGestures { _, offset, zoom, _ -> scale = (scale * zoom).coerceIn(0.5f, 3f); pan += offset } }) {
            Box(Modifier.requiredSize(layout.width.dp, layout.height.dp).graphicsLayer { transformOrigin = TransformOrigin(0f, 0f); scaleX = scale; scaleY = scale; translationX = pan.x; translationY = pan.y }) {
                Canvas(Modifier.fillMaxSize()) {
                    graph.edges.forEachIndexed { index, edge ->
                        val a = layout.points[edge.fromUnitId]; val b = layout.points[edge.toUnitId]
                        if (a != null && b != null) {
                            val from = Offset(a.first.dp.toPx(), a.second.dp.toPx()); val to = Offset(b.first.dp.toPx(), b.second.dp.toPx())
                            drawLine(Color.Gray, from, to, 3f)
                            val dx = to.x - from.x; val dy = to.y - from.y; val length = kotlin.math.sqrt(dx * dx + dy * dy)
                            if (length > 0f) { val ux = dx / length; val uy = dy / length; val tip = Offset(from.x + dx * .65f, from.y + dy * .65f)
                                drawLine(Color.Gray, tip, Offset(tip.x - ux * 14f - uy * 7f, tip.y - uy * 14f + ux * 7f), 3f)
                                drawLine(Color.Gray, tip, Offset(tip.x - ux * 14f + uy * 7f, tip.y - uy * 14f - ux * 7f), 3f)
                            }
                            drawContext.canvas.nativeCanvas.drawText("${index + 1}", (from.x + to.x) / 2, (from.y + to.y) / 2, android.graphics.Paint().apply { color = android.graphics.Color.DKGRAY; textSize = 30f })
                        }
                    }
                }
                nodes.forEach { id -> layout.points[id]?.let { (x, y) ->
                    Surface(Modifier.offset((x - 80).dp, (y - 30).dp).size(160.dp, 60.dp).clickable { onNode(id) }, shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer) {
                        Box(contentAlignment = Alignment.Center) { Text(localRun.units[id]?.title.orEmpty(), maxLines = 2, style = MaterialTheme.typography.bodySmall) }
                    }
                } }
            }
        }
        graph.edges.forEachIndexed { index, edge -> Text("${index + 1}. ${localRun.units[edge.fromUnitId]?.title.orEmpty()} → ${localRun.units[edge.toUnitId]?.title.orEmpty()} · ${relationLabel(edge.relation)}", style = MaterialTheme.typography.bodySmall) }
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (pending) LinearProgressIndicator(Modifier.fillMaxWidth())
    TextButton(onClick = { confirm = true }, enabled = enabled && connection.isConfigured() && !pending) { Text(if (localRun.graph == null) tr("生成知识结构图", "Generate knowledge map") else tr("重新生成知识结构图", "Regenerate knowledge map")) }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text(tr("确认发送", "Confirm sending")) }, text = { Text(tr("将发送课堂文字、课程上下文、疑问和注释、图片时间索引及已生成资料到配置的服务；不会发送音频、图片像素或路径。", "Classroom text, course context, questions, notes, photo time indexes and generated material will be sent to the configured service. Audio, image pixels and paths are not sent.")) }, confirmButton = { TextButton(onClick = { confirm = false; pending = true; error = null; scope.launch { try { localRun = runtime.generateGraph(localRun, connection) } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message ?: tr("生成失败", "Generation failed") } finally { pending = false } } }) { Text(tr("发送", "Send")) } }, dismissButton = { TextButton(onClick = { confirm = false }) { Text(tr("取消", "Cancel")) } })
}

internal data class GraphLayout(val width: Int, val height: Int, val points: Map<String, Pair<Int, Int>>)
internal fun graphLayout(nodes: List<String>, edges: List<ReviewGraphEdge>): GraphLayout {
    val levels = nodes.associateWith { 0 }.toMutableMap()
    // Only advance along acyclic forward relationships. Cross-links and cycles remain visible as edges.
    val forward = edges.filter { it.relation == "derives" || it.relation == "depends_on" }
    val incoming = nodes.associateWith { id -> forward.count { it.toUnitId == id && it.fromUnitId in levels } }.toMutableMap()
    val queue = ArrayDeque(nodes.filter { incoming[it] == 0 })
    while (queue.isNotEmpty()) {
        val id = queue.removeFirst()
        forward.filter { it.fromUnitId == id && it.toUnitId in incoming }.forEach { edge ->
            levels[edge.toUnitId] = maxOf(levels.getValue(edge.toUnitId), levels.getValue(id) + 1)
            incoming[edge.toUnitId] = incoming.getValue(edge.toUnitId) - 1
            if (incoming[edge.toUnitId] == 0) queue.addLast(edge.toUnitId)
        }
    }
    val groups = nodes.groupBy { levels.getValue(it) }.toSortedMap()
    val width = maxOf(200, (groups.values.maxOfOrNull { it.size } ?: 1) * 190)
    val points = mutableMapOf<String, Pair<Int, Int>>()
    groups.entries.forEachIndexed { row, entry -> entry.value.forEachIndexed { col, id -> points[id] = Pair((col * 190) + 95, row * 110 + 55) } }
    return GraphLayout(width, maxOf(300, groups.size * 110), points)
}

@Composable
private fun relationLabel(relation: String): String = when (relation) {
    "derives" -> tr("推导", "Derives")
    "depends_on" -> tr("依赖", "Depends on")
    "parallel" -> tr("并列", "Parallel")
    "related" -> tr("相关", "Related")
    else -> relation
}
