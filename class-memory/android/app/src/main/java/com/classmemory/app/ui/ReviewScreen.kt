package com.classmemory.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.classmemory.app.i18n.AppLanguage
import com.classmemory.app.i18n.tr
import com.classmemory.app.repository.SessionRepository
import com.classmemory.app.review.*
import com.classmemory.app.ui.theme.ClassMemoryColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun ReviewScreen(
    repo: SessionRepository,
    runtime: ReviewRuntime,
    sessionId: String,
    onBack: () -> Unit,
    onSource: (ReviewSource) -> Unit,
    onPhoto: (ReviewPhotoRef) -> Unit,
    onSettings: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val language = if (AppLanguage.isEnglish) "en" else "zh"
    val changes by runtime.store.changes.collectAsState()
    val progress by runtime.progress.collectAsState()
    val runtimeErrors by runtime.errors.collectAsState()
    var run by remember(sessionId) { mutableStateOf<ReviewRun?>(null) }
    var prepared by remember(sessionId) { mutableStateOf<ReviewInput?>(null) }
    var consent by remember(sessionId) { mutableStateOf<ReviewInput?>(null) }
    var error by remember(sessionId) { mutableStateOf<String?>(null) }
    var loaded by remember(sessionId) { mutableStateOf(false) }
    var current by remember(sessionId) { mutableStateOf<Boolean?>(null) }
    var marking by remember { mutableStateOf(false) }
    var history by remember(sessionId) { mutableStateOf<List<ReviewRun>>(emptyList()) }
    var showHistory by remember { mutableStateOf(false) }
    var boardPhotos by remember { mutableStateOf<List<ReviewPhotoRef>>(emptyList()) }
    var questionPanel by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val connection = ReviewConnectionPreferences.load(context)
    val running = progress.containsKey(sessionId)
    val stale = current == false
    LaunchedEffect(sessionId, changes, language) {
        current = null
        try {
            val latest = runtime.store.latest(sessionId)
            run = latest
            history = runtime.store.listVersions(sessionId).filter { it.input.sourceHash != latest?.input?.sourceHash }
            val input = repo.prepareReview(sessionId, language)
            val matches = latest?.let { runtime.isCurrent(it, input) }
            prepared = input
            current = matches
            error = null
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: tr("无法读取课堂", "Could not load classroom") }
        finally { loaded = true }
    }
    val shown = run
    Scaffold(containerColor = ClassMemoryColors.current.background, floatingActionButton = {
        if (shown != null) FloatingActionButton(onClick = { questionPanel = true }) { Text("?", style = MaterialTheme.typography.titleLarge) }
    }, topBar = {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(tr("返回", "Back")) }
            Text(tr("课堂复习", "Classroom review"), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onSettings) { Text(tr("服务设置", "Service")) }
        }
    }) { padding ->
        // Do not measure a temporary empty list and clamp a restored reading position.
        if (!loaded && shown == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding), state = listState, contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Text(shown?.input?.title ?: prepared?.title ?: tr("课堂复习资料", "Classroom review material"), style = MaterialTheme.typography.headlineSmall)
                if (history.isNotEmpty()) TextButton(onClick = { showHistory = true }) { Text(tr("查看旧版复习资料与问答", "View earlier review material and questions")) }
                if (stale) Text(tr("原文或课程上下文已改变，以下资料已过期。重新分析会保留旧版记录。", "Source or course context changed. Material below is outdated; analyzing again keeps the previous version."), color = MaterialTheme.colorScheme.error)
                if (!loaded) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                runtimeErrors[sessionId]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            item {
                if (running) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(progressLabel(progress[sessionId].orEmpty()))
                    TextButton(onClick = { runtime.cancel(sessionId) }) { Text(tr("取消分析", "Cancel analysis")) }
                } else {
                    if (!connection.isConfigured()) Text(tr("尚未配置复习服务。可以继续阅读已保存资料，或前往服务设置。", "Review service is not configured. Saved material remains readable; configure the service to analyze."))
                    if (shown?.plan == null || stale || shown.input.analysisVersion < 3 || (!shown.notesProcessed && shown.input.notes.isNotEmpty())) Button(enabled = prepared != null && connection.isConfigured(), modifier = Modifier.fillMaxWidth(), onClick = {
                        scope.launch {
                            try { consent = repo.prepareReview(sessionId, language) }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (failure: Exception) { error = failure.message ?: tr("准备失败", "Could not prepare") }
                        }
                    }) { Text(when { stale -> tr("预览并分析最新内容", "Preview and analyze latest content"); shown != null && shown.input.analysisVersion < 3 -> tr("按新方式重新整理", "Reorganize with the new approach"); (shown?.errors?.isNotEmpty() == true || (shown != null && !shown.notesProcessed && shown.input.notes.isNotEmpty())) -> tr("预览并重试失败部分", "Preview and retry failed stages"); else -> tr("生成完整复习题案", "Create review scheme") }) }
                }
            }
            if (shown != null) {
                if (shown.errors.isNotEmpty()) item {
                    ReviewBlock(tr("尚未完成", "Incomplete")) {
                        shown.errors.forEach { (stage, message) -> Text("${progressLabel(stage)}: $message", color = MaterialTheme.colorScheme.error) }
                        Text(tr("已成功的部分已保存，重试仅处理失败或缺失部分。", "Successful stages are saved. Retry processes only failed or missing stages."))
                    }
                }
                val orderedUnits = shown.outline?.topics?.mapNotNull { shown.units[it.id] }
                    ?: shown.units.values.toList()
                val unitStart = 2 + (if (shown.errors.isNotEmpty()) 1 else 0) + (if (shown.outline != null) 1 else 0) + (if (shown.plan != null) 1 else 0)
                shown.outline?.let { outline ->
                    item {
                        ReviewBlock(tr("全课导览", "Whole-class guide")) {
                            Text(outline.overview)
                            ReviewGraphSection(shown, connection, runtime, current == true, onNode = { id ->
                                val index = orderedUnits.indexOfFirst { it.id == id }
                                if (index >= 0) scope.launch { listState.animateScrollToItem(unitStart + index) }
                            })
                            orderedUnits.forEachIndexed { index, unit ->
                                val first = unit.sourceIds.mapNotNull { id -> shown.input.sources.find { it.id == id && it.isCurrent } }.minByOrNull { it.startMs }
                                TextButton(onClick = { scope.launch { listState.animateScrollToItem(unitStart + index) } }) {
                                    Text(listOfNotNull(first?.let { reviewTime(it.startMs) }, unit.title).joinToString(" · "))
                                }
                            }
                        }
                    }
                }
                shown.plan?.let { plan ->
                    item {
                        ReviewBlock(plan.title) {
                            Text(plan.introduction)
                            Text(tr("建议复习日期：${plan.suggestedDate}", "Suggested review date: ${plan.suggestedDate}"))
                            Row(Modifier.fillMaxWidth()) {
                                Text(tr("轮次", "Round"), Modifier.weight(1f))
                                Text(tr("复习任务", "Task"), Modifier.weight(2f))
                                Text(tr("完成标准", "Criteria"), Modifier.weight(2f))
                            }
                            plan.steps.forEachIndexed { index, step ->
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(checked = index in shown.completedStepIndices, enabled = current == true && prepared != null && !running && !marking, onCheckedChange = { checked ->
                                            marking = true
                                            scope.launch {
                                                try { runtime.setStepCompleted(shown, index, checked) }
                                                catch (cancelled: CancellationException) { throw cancelled }
                                                catch (failure: Exception) { error = failure.message ?: tr("保存失败", "Could not save") }
                                                finally { marking = false }
                                            }
                                        })
                                        Text("${index + 1}")
                                    }
                                    Text(step.task, Modifier.weight(2f))
                                    Text(step.completionCriteria, Modifier.weight(2f))
                                }
                            }
                        }
                    }
                }
                items(orderedUnits, key = { "unit:${it.id}" }) { unit ->
                    ReviewBlock(unit.title) {
                        if (unit.markdown.isNotBlank()) ReviewContent(unit.markdown) else {
                            ReviewContent(listOfNotNull(unit.explanation, unit.conditions.takeIf { it.isNotBlank() }, unit.classroomFocus, unit.example?.takeIf { it.isNotBlank() }).joinToString("\n\n"))
                        }
                        shown.unitVersions[unit.id]?.takeIf { it.isNotEmpty() }?.let { versions ->
                            var showVersions by remember(unit.id) { mutableStateOf(false) }
                            TextButton(onClick = { showVersions = !showVersions }) { Text(tr("查看 / 收起旧版讲解", "Show / hide earlier explanations")) }
                            if (showVersions) versions.forEachIndexed { index, markdown ->
                                Text(tr("旧版 ${index + 1}", "Earlier version ${index + 1}"), style = MaterialTheme.typography.titleSmall)
                                ReviewContent(markdown)
                            }
                        }
                        ReviewQuestions(shown.qaThreads.filter { it.targetUnitId == unit.id })
                        val source = unit.sourceIds.mapNotNull { id -> shown.input.sources.find { it.id == id } }
                            .sortedWith(compareBy<ReviewSource>({ !it.isCurrent }, { it.startMs }, { it.sessionId }, { it.id })).firstOrNull()
                        source?.let { TextButton(onClick = { onSource(it) }) { Text(tr("索引到出处", "Open source")) } }
                        val photos = unit.photoIds.mapNotNull { id -> shown.input.photos.find { it.id == id } }
                        if (photos.isNotEmpty()) TextButton(onClick = { boardPhotos = photos }) { Text(tr("查看板书", "View board")) }

                    }
                }
                item { ReviewQuestions(shown.qaThreads.filter { it.targetUnitId == null }, wholeClass = true) }
            }
        }
    }
    if (questionPanel && shown != null) ReviewQuestionDialog(shown, connection, runtime, current == true, onClose = { questionPanel = false })
    if (showHistory) Dialog(onDismissRequest = { showHistory = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().systemBarsPadding()) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Text(tr("旧版复习资料与问答", "Earlier review material and questions"), style = MaterialTheme.typography.titleLarge)
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    history.forEachIndexed { version, old ->
                        item { Text(tr("旧版 ${version + 1} · ${old.input.today}", "Earlier version ${version + 1} · ${old.input.today}"), style = MaterialTheme.typography.titleMedium) }
                        val oldUnits = old.outline?.topics?.mapNotNull { old.units[it.id] } ?: old.units.values.toList()
                        items(oldUnits, key = { "old:${old.input.sourceHash}:${it.id}" }) { unit ->
                            ReviewBlock(unit.title) {
                                ReviewContent(unit.markdown.ifBlank { listOfNotNull(unit.explanation, unit.conditions, unit.classroomFocus, unit.example).joinToString("\n\n") })
                                ReviewQuestions(old.qaThreads.filter { it.targetUnitId == unit.id })
                            }
                        }
                        item { ReviewQuestions(old.qaThreads.filter { it.targetUnitId == null }, wholeClass = true) }
                    }
                }
                TextButton(onClick = { showHistory = false }) { Text(tr("关闭", "Close")) }
            }
        }
    }
    if (boardPhotos.isNotEmpty()) {
        AlertDialog(onDismissRequest = { boardPhotos = emptyList() }, title = { Text(tr("关联板书", "Related board photos")) }, text = {
            Column { boardPhotos.forEachIndexed { index, photo ->
                TextButton(onClick = { boardPhotos = emptyList(); onPhoto(photo) }) { Text(tr("板书 ${index + 1}", "Board ${index + 1}")) }
            } }
        }, confirmButton = { TextButton(onClick = { boardPhotos = emptyList() }) { Text(tr("关闭", "Close")) } })
    }
    consent?.let { input ->
        ReviewConsent(input, connection.mode, onDismiss = { consent = null }, onConfirm = {
            runtime.start(input, ReviewConnectionPreferences.load(context))
            consent = null
        })
    }
}

@Composable
private fun ReviewBlock(title: String, body: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            body()
        }
    }
}

@Composable
private fun SourceButton(source: ReviewSource, onSource: (ReviewSource) -> Unit) {
    TextButton(onClick = { onSource(source) }, modifier = Modifier.fillMaxWidth()) {
        Text((if (source.isCurrent) tr("本课", "This class") else tr("历史课堂", "Earlier class")) + " ${reviewTime(source.startMs)}–${reviewTime(source.endMs)}")
    }
}

private fun reviewTime(ms: Long): String {
    val s = ms / 1000
    return "%02d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
}
private fun progressLabel(stage: String): String = when {
    stage == "outline" -> tr("正在通读课堂并划分区间", "Reading the class and building sections")
    stage.startsWith("unit:") -> tr("正在整理知识点 ${stage.removePrefix("unit:")}", "Preparing topic ${stage.removePrefix("unit:")}")
    stage == "plan" -> tr("正在合成完整复习题案", "Combining the review scheme")
    else -> stage
}

@Composable
private fun ReviewConsent(input: ReviewInput, mode: ReviewConnectionMode, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().systemBarsPadding()) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Text(tr("确认发送的课堂资料", "Confirm classroom material to send"), style = MaterialTheme.typography.titleLarge)
                Text(if (mode == ReviewConnectionMode.DIRECT_DEEPSEEK) {
                    tr("以下课堂文字、疑问与注释、课程上下文以及图片时间索引将由手机直接发送给 DeepSeek。音频和板书图片保留在本地。", "This phone will send the classroom text, questions, notes, course context and photo time indexes below directly to DeepSeek. Audio and board photos stay on the device.")
                } else {
                    tr("以下课堂文字、疑问与注释、课程上下文以及图片时间索引将发送到你配置的代理及其模型供应商。音频和板书图片保留在本地。", "The classroom text, questions, notes, course context and photo time indexes below will be sent to your configured proxy and its model provider. Audio and board photos stay on the device.")
                })
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
                    item { Text("${input.title.orEmpty()}\n${tr("生成日期", "Generation date")}: ${input.today}\n${tr("语言", "Language")}: ${input.language}\n${tr("课堂标识", "Class ID")}: ${input.sessionId}\n${tr("内容指纹", "Content hash")}: ${input.sourceHash}") }
                    input.course?.let { c -> item {
                        ReviewBlock(tr("课程上下文", "Course context")) {
                            Text("${c.name}\nID: ${c.id} · ${tr("版本", "Revision")}: ${c.revision}")
                            Text("${tr("背景", "Background")}: ${c.background}\n${tr("目标", "Goals")}: ${c.goals}\n${tr("章节", "Chapters")}: ${c.chapters}\n${tr("术语", "Terminology")}: ${c.terminology}\n${tr("先修关系", "Prerequisites")}: ${c.prerequisites}\n${tr("来源", "Sources")}: ${c.sources}")
                        }
                    } }
                    items(input.sources, key = { "source:${it.id}" }) { s ->
                        ReviewBlock((if (s.isCurrent) tr("本课原文", "Current source") else tr("同课程历史原文", "Same-course history")) + " ${reviewTime(s.startMs)}–${reviewTime(s.endMs)}") {
                            Text("${s.sessionId} · ${s.segmentId} · ${s.id}", style = MaterialTheme.typography.bodySmall)
                            Text(s.text)
                        }
                    }
                    items(input.notes, key = { "note:${it.id}" }) { n -> ReviewBlock("${n.kind} ${reviewTime(n.atMs)} · ${n.id}") { Text(n.text) } }
                    items(input.photos, key = { "photo:${it.id}" }) { p -> Text(tr("图片索引", "Photo index") + ": ${p.id} · ${p.sessionId} · ${reviewTime(p.atMs)}") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text(tr("取消", "Cancel")) }
                    Button(onClick = onConfirm, modifier = Modifier.weight(1f)) { Text(tr("同意并开始", "Agree and start")) }
                }
            }
        }
    }
}
