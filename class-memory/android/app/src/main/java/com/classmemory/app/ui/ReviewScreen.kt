package com.classmemory.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

@Composable
fun ReviewScreen(
    repo: SessionRepository,
    runtime: ReviewRuntime,
    sessionId: String,
    onBack: () -> Unit,
    onSource: (ReviewSource) -> Unit,
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
    var photoConsent by remember(sessionId) { mutableStateOf(ReviewPhotoConsentState()) }
    var error by remember(sessionId) { mutableStateOf<String?>(null) }
    var loaded by remember(sessionId) { mutableStateOf(false) }
    var current by remember(sessionId) { mutableStateOf<Boolean?>(null) }
    var marking by remember { mutableStateOf(false) }
    var history by remember(sessionId) { mutableStateOf<List<ReviewRun>>(emptyList()) }
    var showHistory by remember { mutableStateOf(false) }
    var boardPhotos by remember { mutableStateOf<List<ReviewPhotoRef>>(emptyList()) }
    var questionPanel by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }
    var exerciseBusy by remember(sessionId) { mutableStateOf(false) }
    var exerciseJob by remember(sessionId) { mutableStateOf<Job?>(null) }
    var exerciseError by remember(sessionId) { mutableStateOf<String?>(null) }
    val connection = ReviewConnectionPreferences.load(context)
    val running = progress.containsKey(sessionId)
    val stale = current == false
    LaunchedEffect(sessionId, changes, language) {
        current = null
        try {
            val latest = runtime.store.latest(sessionId)
            run = latest
            history = runtime.store.listVersions(sessionId).filter { it.input.sourceHash != latest?.input?.sourceHash }
            val input = repo.prepareReview(sessionId, language, includeBoardPhotos = false)
            prepared = input
            error = null
            current = latest?.let {
                try { runtime.isCurrent(it, input) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    error = failure.message ?: tr("原板书已不可用，可重新进行文字分析。", "Previous board photos are unavailable. You can analyze the text again.")
                    false
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: tr("无法读取课堂", "Could not load classroom") }
        finally { loaded = true }
    }
    val shown = run
    val orderedUnits = shown?.let(::orderedReviewUnits).orEmpty()
    fun jumpToUnit(id: String) {
        scope.launch {
            reviewUnitIndex(run, id)?.let { listState.animateScrollToItem(it) }
        }
    }
    fun jumpToExercise(id: String? = null) {
        scope.launch { reviewExerciseIndex(run, id)?.let { listState.animateScrollToItem(it) } }
    }
    fun requestExercise(exercise: ReviewExercise? = null, answer: String = "", retryId: String? = null) {
        val selectedRun = run ?: return
        if (exerciseBusy || current != true || running || !connection.isConfigured() || connection.mode != ReviewConnectionMode.DIRECT_DEEPSEEK) return
        exerciseBusy = true
        exerciseError = null
        exerciseJob = scope.launch {
            try {
                if (exercise == null) runtime.generateExercises(selectedRun, connection)
                else {
                    if (retryId == null) runtime.store.saveExerciseDraft(sessionId, selectedRun.input.sourceHash, exercise.id, answer)
                    runtime.submitExercise(selectedRun, connection, exercise.id, answer, retryAttemptId = retryId)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { exerciseError = failure.message ?: tr("练习请求失败，请重试。", "The practice request failed. Try again.") }
            finally { exerciseBusy = false; exerciseJob = null }
        }
    }
    LaunchedEffect(sessionId, shown?.input?.sourceHash) {
        snapshotFlow { run?.exerciseNotices.orEmpty().filterNot { it.consumed } }.collect { notices ->
            for (notice in notices) {
                val activeRun = run ?: break
                try {
                    if (!runtime.ackExerciseNotice(activeRun, notice.id)) continue
                    val result = snackbar.showSnackbar(
                        message = tr("DeepSeek 已追加一道针对练习，已放到末尾。", "DeepSeek added a follow-up question at the end."),
                        actionLabel = tr("查看", "View"),
                        duration = SnackbarDuration.Long,
                    )
                    if (result == SnackbarResult.ActionPerformed) jumpToExercise(notice.exerciseId)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { exerciseError = tr("无法保存练习提醒状态，请重新打开页面。", "Could not save practice notification state. Reopen this page.") }
            }
        }
    }
    fun prepareConsent(include: Boolean, retry: ReviewRun? = null) {
        photoConsent = photoConsent.request(include)
        scope.launch {
            try {
                check(!include || connection.mode == ReviewConnectionMode.DIRECT_DEEPSEEK) {
                    tr("板书分析需要手机直连 DeepSeek，也可仅用文字继续。", "Photo analysis requires a direct DeepSeek connection. You can continue with text only.")
                }
                val next = if (include && retry != null) {
                    check(runtime.isCurrent(retry)) {
                        tr("原板书或课堂资料已变化，请重新选图或仅用文字继续。", "The original photos or class material changed. Select photos again or continue with text only.")
                    }
                    retry.input
                } else repo.prepareReview(sessionId, language, includeBoardPhotos = include)
                check(!include || next.selectedVisionPhotoIds.isNotEmpty()) {
                    tr("没有可用板书，可仅用文字继续。", "No board photos are available. You can continue with text only.")
                }
                consent = next
                photoConsent = photoConsent.finish()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                photoConsent = photoConsent.finish(failure.message ?: tr("准备失败", "Could not prepare"))
            }
        }
    }
    fun openConsent(resume: Boolean = false) {
        consent = shown?.input ?: prepared
        val include = resume && reviewRetryIncludesPhotos(shown)
        prepareConsent(include, retry = shown.takeIf { include })
    }
    Scaffold(containerColor = ClassMemoryColors.current.background, floatingActionButton = {
        if (shown != null) FloatingActionButton(onClick = { questionPanel = true }) { Text("?", style = MaterialTheme.typography.titleLarge) }
    }, snackbarHost = { SnackbarHost(snackbar) }, topBar = {
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
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 104.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item(key = "header") {
                Text(shown?.input?.title ?: prepared?.title ?: tr("课堂复习资料", "Classroom review material"), style = MaterialTheme.typography.headlineSmall)
                if (history.isNotEmpty()) TextButton(onClick = { showHistory = true }) { Text(tr("查看旧版复习资料与问答", "View earlier review material and questions")) }
                if (stale) Text(tr("原文或课程上下文已改变，以下资料已过期。重新分析会保留旧版记录。", "Source or course context changed. Material below is outdated; analyzing again keeps the previous version."), color = MaterialTheme.colorScheme.error)
                if (!loaded) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                runtimeErrors[sessionId]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            item(key = "actions") {
                if (running) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(progressLabel(progress[sessionId].orEmpty()))
                    TextButton(onClick = { runtime.cancel(sessionId) }) { Text(tr("取消分析", "Cancel analysis")) }
                } else {
                    if (!connection.isConfigured()) Text(tr("尚未配置复习服务。可以继续阅读已保存资料，或前往服务设置。", "Review service is not configured. Saved material remains readable; configure the service to analyze."))
                    if (shown?.plan == null || stale || shown.input.analysisVersion < 3 || shown.errors.isNotEmpty() || (!shown.notesProcessed && shown.input.notes.isNotEmpty())) Button(enabled = prepared != null && connection.isConfigured(), modifier = Modifier.fillMaxWidth(), onClick = { openConsent(resume = true) }) { Text(when { stale -> tr("预览并分析最新内容", "Preview and analyze latest content"); shown != null && shown.input.analysisVersion < 3 -> tr("按新方式重新整理", "Reorganize with the new approach"); (shown?.errors?.isNotEmpty() == true || (shown != null && !shown.notesProcessed && shown.input.notes.isNotEmpty())) -> tr("预览并重试失败部分", "Preview and retry failed stages"); else -> tr("生成完整复习题案", "Create review scheme") }) }
                    if (shown?.plan != null && connection.mode == ReviewConnectionMode.DIRECT_DEEPSEEK && prepared?.photos?.any { it.sessionId == sessionId } == true) {
                        OutlinedButton(onClick = { openConsent() }, enabled = connection.isConfigured(), modifier = Modifier.fillMaxWidth()) {
                            Text(tr("结合板书重新整理", "Reorganize with board photos"))
                        }
                    }
                }
            }
            if (shown != null) {
                if (shown.errors.isNotEmpty()) item(key = "errors") {
                    ReviewBlock(tr("尚未完成", "Incomplete")) {
                        shown.errors.forEach { (stage, message) -> Text("${progressLabel(stage)}: $message", color = MaterialTheme.colorScheme.error) }
                        Text(tr("已成功的部分已保存，重试仅处理失败或缺失部分。", "Successful stages are saved. Retry processes only failed or missing stages."))
                    }
                }
                shown.outline?.let { outline ->
                    item(key = "outline") {
                        ReviewBlock(tr("全课导览", "Whole-class guide")) {
                            Text(outline.overview)
                            ReviewGraphSection(shown, connection, runtime, current == true, onNode = { id ->
                                jumpToUnit(id)
                            })
                            orderedUnits.forEach { unit ->
                                val first = unit.sourceIds.mapNotNull { id -> shown.input.sources.find { it.id == id && it.isCurrent } }.minByOrNull { it.startMs }
                                TextButton(onClick = { jumpToUnit(unit.id) }) {
                                    Text(listOfNotNull(first?.let { reviewTime(it.startMs) }, unit.title).joinToString(" · "))
                                }
                            }
                        }
                    }
                }
                shown.plan?.let { plan ->
                    item(key = "plan") {
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
                            var showVersions by rememberSaveable(unit.id) { mutableStateOf(false) }
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
                item(key = "whole-class-questions") { ReviewQuestions(shown.qaThreads.filter { it.targetUnitId == null }, wholeClass = true) }
                item(key = "exercises") {
                    ReviewExerciseHeader(
                        shown, connection, current == true && !running, exerciseBusy, exerciseError,
                        onGenerate = { requestExercise() },
                        onCancel = { exerciseJob?.cancel() },
                    )
                }
                items(shown.exercises, key = { "exercise:${it.id}" }) { exercise ->
                    ReviewExerciseCard(
                        shown, exercise, shown.exercises.indexOfFirst { it.id == exercise.id } + 1,
                        runtime, scope,
                        enabled = current == true && !running && connection.isConfigured() && connection.mode == ReviewConnectionMode.DIRECT_DEEPSEEK,
                        busy = exerciseBusy,
                        onSubmit = ::requestExercise,
                        onCancel = { exerciseJob?.cancel() },
                    )
                }
            }
        }
        ReviewQuickDirectory(
            units = orderedUnits,
            maxHeight = (maxHeight * 0.55f).coerceAtMost(360.dp),
            modifier = Modifier.align(Alignment.CenterEnd).padding(bottom = 88.dp),
            onSelect = ::jumpToUnit,
            onExercises = if (shown == null) null else ({ jumpToExercise() }),
        )
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
                        if (old.exercises.isNotEmpty()) item { Text(tr("课后练习", "After-class practice"), style = MaterialTheme.typography.titleMedium) }
                        items(old.exercises, key = { "old-exercise:${old.input.sourceHash}:${it.id}" }) { exercise ->
                            ReviewExerciseHistory(exercise, old.exercises.indexOfFirst { it.id == exercise.id } + 1)
                        }
                    }
                }
                TextButton(onClick = { showHistory = false }) { Text(tr("关闭", "Close")) }
            }
        }
    }
    consent?.let { input ->
        ReviewConsent(input, connection.mode, photoConsent, onIncludePhotos = { include ->
            prepareConsent(include)
        }, onPreviewPhotos = { boardPhotos = it }, onDismiss = { consent = null }, onConfirm = {
            runtime.start(input, ReviewConnectionPreferences.load(context))
            consent = null
        })
    }
    if (boardPhotos.isNotEmpty()) ReviewBoardPhotos(boardPhotos, repo, onClose = { boardPhotos = emptyList() })
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
    stage.startsWith("photo:") -> tr("正在分析板书", "Analyzing board photos")
    stage == "plan" -> tr("正在合成完整复习题案", "Combining the review scheme")
    else -> stage
}

@Composable
private fun ReviewConsent(
    input: ReviewInput,
    mode: ReviewConnectionMode,
    selection: ReviewPhotoConsentState,
    onIncludePhotos: (Boolean) -> Unit,
    onPreviewPhotos: (List<ReviewPhotoRef>) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val preparing = selection.preparing
    val error = selection.error
    val selectedPhotos = input.photos.filter { it.id in input.selectedVisionPhotoIds }
    val currentPhotoCount = input.photos.count { it.sessionId == input.sessionId }
    val requestedPhotoCount = if (selectedPhotos.isEmpty()) currentPhotoCount else selectedPhotos.size
    Dialog(onDismissRequest = { if (!preparing) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().systemBarsPadding()) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Text(tr("确认发送的课堂资料", "Confirm classroom material to send"), style = MaterialTheme.typography.titleLarge)
                Text(if (mode == ReviewConnectionMode.DIRECT_DEEPSEEK) {
                    if (!selection.includeRequested) {
                        tr("以下课堂文字、疑问与注释、课程上下文及图片时间索引将直接发送给 DeepSeek。本次保持纯文字分析。", "The classroom text, questions, notes, course context and photo time indexes will go directly to DeepSeek. This request uses text analysis.")
                    } else {
                        tr("先将选中的 ${requestedPhotoCount} 张本课板书上传给 DeepSeek 单独分析，再将图片分析文字与课堂资料合并整理。音频保留在本地。", "First upload the ${requestedPhotoCount} selected board photos to DeepSeek for image analysis, then combine the resulting observations with classroom material. Audio stays on the device.")
                    }
                } else {
                    tr("以下课堂文字、疑问与注释、课程上下文以及图片时间索引将发送到你配置的代理及其模型供应商。音频和板书图片保留在本地。", "The classroom text, questions, notes, course context and photo time indexes below will be sent to your configured proxy and its model provider. Audio and board photos stay on the device.")
                })
                if (mode == ReviewConnectionMode.DIRECT_DEEPSEEK) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = selection.includeRequested, enabled = !preparing && (currentPhotoCount > 0 || selection.includeRequested), onCheckedChange = onIncludePhotos)
                    Text(tr("结合本课板书分析（上传 ${requestedPhotoCount} 张）", "Include this class's board photos (upload ${requestedPhotoCount})"), Modifier.weight(1f))
                }
                if (preparing) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                    if (selection.includeRequested) TextButton(enabled = !preparing, onClick = { onIncludePhotos(false) }) {
                        Text(tr("仅用文字继续", "Continue with text only"))
                    }
                }
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
                    items(input.photos, key = { "photo:${it.id}" }) { p ->
                        val label = if (p.id in input.selectedVisionPhotoIds) tr("将上传板书", "Board photo to upload") else tr("仅图片索引", "Photo index only")
                        TextButton(onClick = { onPreviewPhotos(listOf(p)) }) {
                            Text("$label · ${reviewTime(p.atMs)} · " + tr("本地查看", "View locally"))
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onDismiss, enabled = !preparing, modifier = Modifier.weight(1f)) { Text(tr("取消", "Cancel")) }
                    Button(onClick = onConfirm, enabled = selection.canConfirm(input), modifier = Modifier.weight(1f)) { Text(tr("同意并开始", "Agree and start")) }
                }
            }
        }
    }
}
