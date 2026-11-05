package com.classmemory.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.TransformOrigin
import kotlinx.coroutines.Job
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.classmemory.app.ui.icons.ClassMemoryIcons
import com.classmemory.app.ui.theme.ClassMemoryColors
import com.classmemory.app.i18n.tr
import com.classmemory.app.review.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun ReviewQuestionCard(snapshotKey: String, threadId: String, turn: ReviewQaTurn, actions: (@Composable () -> Unit)? = null) {
    val disclosure = reviewDisclosureKey(snapshotKey, "question", threadId, turn.id)
    var expanded by rememberSaveable(disclosure) { mutableStateOf(true) }
    val p = ClassMemoryColors.current
    ReviewPaper {
        ReviewDisclosureHeader(turn.question, expanded) { expanded = !expanded }
        if (expanded) {
            ReviewBadge(when (turn.status) { "answered" -> tr("已回答", "Answered"); "pending" -> tr("待处理", "Pending"); else -> tr("处理失败", "Failed") },
                if (turn.status == "answered") p.successBackground else p.warningBackground,
                if (turn.status == "answered") p.success else p.warning)
            HorizontalDivider(color = p.border)
            if (turn.status == "answered") ReviewContent(turn.answerMarkdown)
            else Text(if (turn.status == "pending") tr("问题已保存，正在等待处理。", "Your question is saved and awaiting an answer.") else tr("问题已保存，可进入此对话重试。", "Your question is saved. Open the conversation to retry."), color = p.muted)
            if (turn.clarificationNeeded) Text(tr("需要进一步澄清", "Further clarification needed"), color = p.warning)
            actions?.invoke()
        }
    }
}

@Composable
internal fun ReviewDisclosureHeader(title: String, expanded: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
        stateDescription = if (expanded) tr("已展开", "Expanded") else tr("已收起", "Collapsed")
    }, contentPadding = PaddingValues(0.dp)) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
            maxLines = if (expanded) Int.MAX_VALUE else 2,
            overflow = if (expanded) TextOverflow.Clip else TextOverflow.Ellipsis)
        Text(if (expanded) "⌃" else "⌄", Modifier.padding(start = 12.dp), style = MaterialTheme.typography.titleMedium)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReviewQuestionDialog(run: ReviewRun, connection: ReviewConnection, runtime: ReviewRuntime, enabled: Boolean, onClose: () -> Unit, initialTarget: String? = null, initialThread: String? = null) {
    val scope = rememberCoroutineScope()
    val p = ClassMemoryColors.current
    var target by rememberSaveable { mutableStateOf(initialTarget) }
    var threadId by rememberSaveable { mutableStateOf(initialThread) }
    var question by rememberSaveable { mutableStateOf("") }
    var retryId by rememberSaveable { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var pending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf(false) }
    var scopeMenu by remember { mutableStateOf(false) }
    var discardDraft by remember { mutableStateOf(false) }
    fun requestClose() {
        when {
            pending -> { job?.cancel(); onClose() }
            question.isNotBlank() -> discardDraft = true
            else -> onClose()
        }
    }
    var localRun by remember(run) { mutableStateOf(run) }
    val units = orderedReviewUnits(run)
    ModalBottomSheet(onDismissRequest = { if (!pending) requestClose() }, containerColor = p.background,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { next ->
            when {
                next != SheetValue.Hidden -> true
                pending -> false
                question.isNotBlank() -> { discardDraft = true; false }
                else -> true
            }
        })) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f).imePadding().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("课堂提问", "Ask your class"), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = ::requestClose) { Icon(ClassMemoryIcons.Close, tr("关闭提问", "Close questions")) }
            }
            Text(tr("提问范围", "Question scope"), style = MaterialTheme.typography.titleSmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) {
                    OutlinedButton(onClick = { scopeMenu = true }, enabled = !pending, modifier = Modifier.fillMaxWidth()) {
                        Text(units.firstOrNull { it.id == target }?.let { "K%02d · %s".format(units.indexOf(it) + 1, it.title) } ?: tr("整节课堂", "Whole class"), Modifier.weight(1f))
                        Text("⌄")
                    }
                    DropdownMenu(expanded = scopeMenu, onDismissRequest = { scopeMenu = false }, modifier = Modifier.heightIn(max = 360.dp)) {
                        DropdownMenuItem(text = { Text(tr("整节课堂", "Whole class")) }, onClick = { target = null; threadId = null; retryId = null; scopeMenu = false })
                        units.forEachIndexed { index, unit -> DropdownMenuItem(text = { Text("K%02d · %s".format(index + 1, unit.title)) }, onClick = { target = unit.id; threadId = null; retryId = null; scopeMenu = false }) }
                    }
                }
                if (target != null) TextButton(onClick = { target = null; threadId = null; retryId = null }, enabled = !pending) { Text(tr("全课", "All")) }
            }
            if (threadId != null) Row(verticalAlignment = Alignment.CenterVertically) {
                ReviewBadge(tr("正在追问此对话", "Following this conversation"))
                TextButton(onClick = { threadId = null; retryId = null }, enabled = !pending) { Text(tr("新问题", "New question")) }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val threads = localRun.qaThreads.filter { it.targetUnitId == target }
                if (threads.isEmpty()) item {
                    ReviewPaper {
                        Icon(ClassMemoryIcons.Question, null, Modifier.size(30.dp), tint = p.primary)
                        Text(tr("从没听懂的地方开始", "Start with what is unclear"), style = MaterialTheme.typography.titleMedium)
                        Text(tr("提问会结合课堂原文、课程背景和相关知识讲解。", "Answers use the class transcript, course context and related explanations."), color = p.muted)
                    }
                }
                threads.forEach { thread ->
                    items(thread.turns, key = { reviewDisclosureKey(reviewSnapshotKey(localRun), "question", thread.id, it.id) }) { turn ->
                        ReviewQuestionCard(reviewSnapshotKey(localRun), thread.id, turn) {
                            if (turn.status != "answered") {
                                TextButton(enabled = !pending, onClick = { threadId = thread.id; retryId = turn.id; question = turn.question; error = null }) { Text(tr("重试此问题 ↗", "Retry this question ↗")) }
                            } else TextButton(enabled = !pending, onClick = { threadId = thread.id; retryId = null }) { Text(tr("追问此对话 →", "Continue this conversation →")) }
                        }
                    }
                }
            }
            OutlinedTextField(value = question, onValueChange = { question = it; retryId = null; error = null }, enabled = !pending,
                label = { Text(tr("输入问题", "Your question")) }, modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 4, shape = RoundedCornerShape(12.dp))
            error?.let { Text(it + "\n" + tr("可在上方选择“重试此问题”。", "Select Retry this question above."), color = p.error, style = MaterialTheme.typography.bodySmall) }
            if (!enabled) Text(tr("资料已过期，请先更新。", "Material is outdated; update it first."), color = p.warning)
            if (!connection.isConfigured()) Text(tr("请先在服务设置中配置连接。", "Configure your connection in service settings first."), color = p.muted)
            if (pending) LinearProgressIndicator(Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = ::requestClose, modifier = Modifier.weight(1f)) { Text(if (pending) tr("取消并关闭", "Cancel and close") else tr("关闭", "Close")) }
                Button(onClick = { confirm = true }, modifier = Modifier.weight(1f), enabled = enabled && connection.isConfigured() && question.isNotBlank() && !pending && (error == null || retryId != null)) { Text(if (retryId != null) tr("重试", "Retry") else tr("提问", "Ask")) }
            }
        }
    }
    if (discardDraft) AlertDialog(
        onDismissRequest = { discardDraft = false },
        title = { Text(tr("输入尚未提交", "Your question has not been submitted")) },
        text = { Text(tr("继续编辑可保留这段输入；放弃只清除当前草稿，已保存的问题和回答不受影响。", "Keep editing to retain this input. Discarding only removes this draft; saved questions and answers remain.")) },
        confirmButton = { Button(onClick = { discardDraft = false }) { Text(tr("保留继续编辑", "Keep editing")) } },
        dismissButton = { TextButton(onClick = { discardDraft = false; onClose() }) { Text(tr("放弃草稿", "Discard draft")) } },
    )
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text(tr("确认发送", "Confirm sending")) }, text = { Text(tr("将向配置的服务发送课堂文字、课程上下文、疑问和注释、图片时间索引、已保存板书分析文字、已生成资料、此问题及同一对话历史；不会发送音频、图片像素或图片路径。", "The configured service receives classroom text, course context, questions and notes, photo time indexes, saved board-photo observations, generated material, this question and conversation history. Audio, image pixels and paths are not sent.")) }, confirmButton = { Button(onClick = {
        confirm = false; pending = true; error = null
        job = scope.launch { try { localRun = runtime.ask(localRun, connection, question, target, threadId, retryId); question = ""; retryId = null } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message ?: tr("提问失败", "Question failed") } finally { pending = false } }
    }) { Text(tr("同意并发送", "Agree and send")) } }, dismissButton = { TextButton(onClick = { confirm = false }) { Text(tr("取消", "Cancel")) } })
}

@Composable
internal fun ReviewGraphDialog(run: ReviewRun, connection: ReviewConnection, runtime: ReviewRuntime, enabled: Boolean, onClose: () -> Unit, onNode: (String) -> Unit) {
    val p = ClassMemoryColors.current
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().systemBarsPadding(), color = p.background) {
            Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(ClassMemoryIcons.Back, tr("返回复习", "Back to review")) }
                    Text(tr("思维导图", "Knowledge map"), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                }
                Text(run.input.title.orEmpty(), color = p.muted, style = MaterialTheme.typography.bodyLarge)
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { ReviewGraphSection(run, connection, runtime, enabled, onNode) }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReviewGraphSection(run: ReviewRun, connection: ReviewConnection, runtime: ReviewRuntime, enabled: Boolean, onNode: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var localRun by remember(run) { mutableStateOf(run) }
    val palette = ClassMemoryColors.current
    if (localRun.graph == null) ReviewPaper {
        NotebookIllustration(Modifier.align(Alignment.CenterHorizontally).size(100.dp))
        Text(tr("把知识点连起来", "Connect the topics"), style = MaterialTheme.typography.titleLarge)
        Text(tr("根据本课已经生成的知识讲解，整理它们之间的关系。", "Build relationships from the knowledge explanations already saved for this class."), color = palette.muted)
    }
    localRun.graph?.let { graph ->
        var scale by remember(graph) { mutableFloatStateOf(1f) }
        var pan by remember(graph) { mutableStateOf(Offset.Zero) }
        val nodes = graph.nodes.filter { localRun.units.containsKey(it) }
        val layout = remember(nodes, graph.edges) { graphLayout(nodes, graph.edges) }
        // Use the actual node bounds, not the layout's minimum empty canvas, for framing.
        // Start at readable 100%; fitting a wide graph is an explicit user action.
        val left = (layout.points.values.minOfOrNull { it.first } ?: 95) - 96f
        val top = (layout.points.values.minOfOrNull { it.second } ?: 55) - 62f
        val graphWidth = (layout.points.values.maxOfOrNull { it.first } ?: 95) + 96f - left
        val graphHeight = (layout.points.values.maxOfOrNull { it.second } ?: 55) + 62f - top
        val viewportHeight = (graphHeight + 16f).coerceIn(240f, 400f).dp
        Text(tr("双指缩放、拖动；点击知识点返回讲解", "Pinch to zoom, drag, and tap a topic to open its explanation"), color = palette.muted, style = MaterialTheme.typography.bodySmall)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val fit = minOf(1f, maxWidth.value / graphWidth, viewportHeight.value / graphHeight)
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                TextButton(onClick = { scale = (scale / 1.25f).coerceAtLeast(0.25f) }, modifier = Modifier.widthIn(min = 48.dp).semantics { contentDescription = tr("缩小导图", "Zoom out") }) { Text("−") }
                Text("${(scale * 100).toInt()}%", Modifier.padding(horizontal = 4.dp, vertical = 14.dp), color = palette.muted, style = MaterialTheme.typography.labelMedium)
                TextButton(onClick = { scale = (scale * 1.25f).coerceAtMost(4f) }, modifier = Modifier.widthIn(min = 48.dp).semantics { contentDescription = tr("放大导图", "Zoom in") }) { Text("+") }
                TextButton(onClick = { scale = fit; pan = Offset.Zero }) { Text(tr("适配全图", "Fit all"), style = MaterialTheme.typography.labelMedium) }
                TextButton(onClick = { scale = 1f; pan = Offset.Zero }) { Text(tr("复位", "Reset"), style = MaterialTheme.typography.labelMedium) }
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().height(viewportHeight).clipToBounds().pointerInput(graph) { detectTransformGestures { _, offset, zoom, _ -> scale = (scale * zoom).coerceIn(0.25f, 4f); pan += offset } }) {
            val centeredX = ((maxWidth.value - graphWidth * scale) / 2f).coerceAtLeast(0f)
            val centeredY = ((maxHeight.value - graphHeight * scale) / 2f).coerceAtLeast(0f)
            Box(Modifier.wrapContentSize(Alignment.TopStart, unbounded = true).requiredSize(layout.width.dp, layout.height.dp).graphicsLayer {
                transformOrigin = TransformOrigin(0f, 0f)
                scaleX = scale; scaleY = scale
                translationX = (centeredX - left * scale).dp.toPx() + pan.x
                translationY = (centeredY - top * scale).dp.toPx() + pan.y
            }) {
                Canvas(Modifier.fillMaxSize()) {
                    graph.edges.forEachIndexed { index, edge ->
                        val a = layout.points[edge.fromUnitId]; val b = layout.points[edge.toUnitId]
                        if (a != null && b != null) {
                            val from = Offset(a.first.dp.toPx(), a.second.dp.toPx()); val to = Offset(b.first.dp.toPx(), b.second.dp.toPx())
                            drawLine(palette.primary, from, to, 3f)
                            val dx = to.x - from.x; val dy = to.y - from.y; val length = kotlin.math.sqrt(dx * dx + dy * dy)
                            if (length > 0f) { val ux = dx / length; val uy = dy / length; val tip = Offset(from.x + dx * .65f, from.y + dy * .65f)
                                drawLine(palette.primary, tip, Offset(tip.x - ux * 14f - uy * 7f, tip.y - uy * 14f + ux * 7f), 3f)
                                drawLine(palette.primary, tip, Offset(tip.x - ux * 14f + uy * 7f, tip.y - uy * 14f - ux * 7f), 3f)
                            }
                            drawContext.canvas.nativeCanvas.drawText("${index + 1}", (from.x + to.x) / 2, (from.y + to.y) / 2, android.graphics.Paint().apply { color = palette.primary.toArgb(); textSize = 30f })
                        }
                    }
                }
                nodes.forEach { id -> layout.points[id]?.let { (x, y) ->
                    Surface(Modifier.offset((x - 80).dp, (y - 48).dp).size(160.dp, 96.dp).clickable { onNode(id) }, shape = RoundedCornerShape(13.dp), color = palette.surface, border = BorderStroke(1.dp, palette.primary), shadowElevation = 2.dp) {
                        Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Text("K%02d".format(orderedReviewUnits(localRun).indexOfFirst { it.id == id } + 1), color = palette.primary, style = MaterialTheme.typography.labelSmall)
                            Text(localRun.units[id]?.title.orEmpty(), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                } }
            }
        }
        graph.edges.forEachIndexed { index, edge -> Text("${index + 1}. ${localRun.units[edge.fromUnitId]?.title.orEmpty()} → ${localRun.units[edge.toUnitId]?.title.orEmpty()} · ${relationLabel(edge.relation)}", style = MaterialTheme.typography.bodySmall) }
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (pending) LinearProgressIndicator(Modifier.fillMaxWidth())
    Button(onClick = { confirm = true }, modifier = Modifier.fillMaxWidth(), enabled = enabled && connection.isConfigured() && !pending) { Text(if (localRun.graph == null) tr("生成思维导图", "Generate knowledge map") else tr("重新生成思维导图", "Regenerate knowledge map")) }
    if (!enabled) Text(tr("资料已过期，可浏览原图；请先更新复盘再生成。", "This material is outdated. You can view the saved map; update the review before generating another."), color = palette.muted)
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text(tr("确认发送", "Confirm sending")) }, text = { Text(tr("将发送课堂文字、课程上下文、疑问和注释、图片时间索引、已保存板书分析文字及已生成资料到配置的服务；不会发送音频、图片像素或路径。", "Classroom text, course context, questions, notes, photo time indexes, saved board-photo observations and generated material will be sent to the configured service. Audio, image pixels and paths are not sent.")) }, confirmButton = { TextButton(onClick = { confirm = false; pending = true; error = null; scope.launch { try { localRun = runtime.generateGraph(localRun, connection) } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message ?: tr("生成失败", "Generation failed") } finally { pending = false } } }) { Text(tr("发送", "Send")) } }, dismissButton = { TextButton(onClick = { confirm = false }) { Text(tr("取消", "Cancel")) } })
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
