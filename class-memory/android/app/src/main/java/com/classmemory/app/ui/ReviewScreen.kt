package com.classmemory.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.classmemory.app.ui.icons.ClassMemoryIcons
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.classmemory.app.i18n.AppLanguage
import com.classmemory.app.i18n.tr
import com.classmemory.app.repository.SessionRepository
import com.classmemory.app.review.*
import com.classmemory.app.ui.theme.ClassMemoryColors
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.awaitCancellation

@Composable
fun ReviewScreen(
    repo: SessionRepository,
    runtime: ReviewRuntime,
    sessionId: String,
    onBack: () -> Unit,
    onSource: (ReviewSource) -> Unit,
    onSettings: () -> Unit,
    onTranscript: () -> Unit = onBack,
) = ReviewRenderingScope(sessionId) {
    ReviewScreenContent(repo, runtime, sessionId, onBack, onSource, onSettings, onTranscript)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReviewScreenContent(
    repo: SessionRepository,
    runtime: ReviewRuntime,
    sessionId: String,
    onBack: () -> Unit,
    onSource: (ReviewSource) -> Unit,
    onSettings: () -> Unit,
    onTranscript: () -> Unit,
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
    var questionTarget by remember { mutableStateOf<String?>(null) }
    var questionThread by remember { mutableStateOf<String?>(null) }
    var graphOpen by rememberSaveable(sessionId) { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var directoryOpen by rememberSaveable(sessionId) { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }
    var exerciseBusy by remember(sessionId) { mutableStateOf(false) }
    var exerciseJob by remember(sessionId) { mutableStateOf<Job?>(null) }
    var exerciseError by remember(sessionId) { mutableStateOf<String?>(null) }
    var connection by remember(context) { mutableStateOf(ReviewConnection()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(context, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            // Keystore work happens once on entry/resume, never in scrolling composition.
            connection = withContext(Dispatchers.IO) { ReviewConnectionPreferences.load(context) }
            awaitCancellation()
        }
    }
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
    val orderedUnits = remember(shown?.outline, shown?.units) { shown?.let(::orderedReviewUnits).orEmpty() }
    val questionsByUnit = remember(shown?.qaThreads) { shown?.qaThreads.orEmpty().groupBy { it.targetUnitId } }
    val sourcesById = remember(shown?.input?.sources) { shown?.input?.sources.orEmpty().associateBy { it.id } }
    val photosById = remember(shown?.input?.photos) { shown?.input?.photos.orEmpty().associateBy { it.id } }
    fun jumpToUnit(id: String) {
        scope.launch {
            reviewUnitIndex(run, id)?.let { listState.animateScrollToItem(it) }
        }
    }
    fun jumpToSection(key: String) {
        scope.launch { reviewListKeys(run).indexOf(key).takeIf { it >= 0 }?.let { listState.animateScrollToItem(it) } }
    }
    fun askUnit(unitId: String? = null, threadId: String? = null) {
        questionTarget = unitId; questionThread = threadId; questionPanel = true; directoryOpen = false
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
    val palette = ClassMemoryColors.current
    val keyboardVisible = WindowInsets.isImeVisible
    val showFloatingActions = shown != null && !questionPanel && !graphOpen && !directoryOpen && !keyboardVisible
    Scaffold(containerColor = palette.background, floatingActionButton = {
        if (showFloatingActions) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReviewFloatingAction(tr("目录", "Index"), ClassMemoryIcons.File, false, { directoryOpen = true })
                ReviewFloatingAction(tr("提问", "Ask"), ClassMemoryIcons.Question, true, { askUnit() })
                ReviewFloatingAction(tr("顶部", "Top"), ClassMemoryIcons.ArrowUp, false, { scope.launch { listState.animateScrollToItem(0) } })
            }
        }
    }, snackbarHost = { SnackbarHost(snackbar) }, topBar = {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(ClassMemoryIcons.Back, contentDescription = tr("返回课堂", "Back to class")) }
            Column(Modifier.weight(1f)) {
                Text(tr("课堂复习", "Classroom review"), style = MaterialTheme.typography.titleMedium)
                (shown?.input?.course?.name ?: prepared?.course?.name)?.let {
                    Text(it, color = palette.primary, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(ClassMemoryIcons.More, contentDescription = tr("复习选项", "Review options")) }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text(tr("服务设置", "Service settings")) }, onClick = { menuOpen = false; onSettings() })
                    if (!running && shown?.plan != null && connection.mode == ReviewConnectionMode.DIRECT_DEEPSEEK && prepared?.photos?.any { it.sessionId == sessionId } == true) {
                        DropdownMenuItem(
                            text = { Text(tr("结合板书重新整理", "Reorganize with board photos")) },
                            enabled = connection.isConfigured(),
                            onClick = { menuOpen = false; openConsent() },
                        )
                    }
                    if (history.isNotEmpty()) DropdownMenuItem(text = { Text(tr("旧版资料与问答", "Earlier versions and questions")) }, onClick = { menuOpen = false; showHistory = true })
                    DropdownMenuItem(text = { Text(tr("课堂转录原文", "Class transcript")) }, onClick = { menuOpen = false; onTranscript() })
                }
            }
        }
    }) { padding ->
        // Do not measure a temporary empty list and clamp a restored reading position.
        if (!loaded && shown == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
        // Reserve viewport space, not just trailing content: the dock must never cover a mid-page formula or table.
        LazyColumn(Modifier.fillMaxSize().padding(bottom = if (showFloatingActions) 84.dp else 0.dp), state = listState, contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item(key = "header") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (running) tr("整理课堂", "Organizing your class") else tr("这节课，先看全貌", "See the whole class"), Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                        SketchAccent(Modifier.size(38.dp))
                    }
                    Text(shown?.input?.title ?: prepared?.title ?: tr("课堂复习资料", "Classroom review material"), style = MaterialTheme.typography.bodyLarge, color = palette.muted)
                    Text(tr("课堂  ›  复习资料", "Class  ›  Review material"), color = palette.primary, style = MaterialTheme.typography.labelMedium)
                }
                if (stale) Text(tr("原文或课程上下文已改变，以下资料已过期。重新分析会保留旧版记录。", "Source or course context changed. Material below is outdated; analyzing again keeps the previous version."), color = MaterialTheme.colorScheme.error)
                if (!loaded) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                runtimeErrors[sessionId]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            item(key = "actions") {
                if (running) {
                    ReviewOrganizingHero(shown, progressLabel(progress[sessionId].orEmpty()), shown?.input?.title ?: prepared?.title.orEmpty(), shown?.input?.course?.name ?: prepared?.course?.name) { runtime.cancel(sessionId) }
                } else {
                    if (shown == null) {
                        ReviewPaper {
                            NotebookIllustration(Modifier.align(Alignment.CenterHorizontally).size(100.dp))
                            Text(tr("把这节课，整理成复习材料", "Turn this class into review material"), style = MaterialTheme.typography.titleLarge)
                            Text(tr("结合课堂文字、随记和课程背景，形成有出处的知识讲解与复习清单。", "Use your class transcript, notes and course context to build source-linked explanations and a review checklist."), color = palette.muted)
                        }
                        Spacer(Modifier.height(14.dp))
                    }
                    if (!connection.isConfigured()) {
                        Text(tr("尚未配置复习服务。已保存的资料仍可阅读。", "Review service is not configured. Saved material remains readable."), color = palette.muted, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = onSettings) { Text(tr("前往服务设置 →", "Open service settings →"), style = MaterialTheme.typography.labelMedium) }
                    }
                    if (shown?.plan == null || stale || shown.input.analysisVersion < 3 || shown.errors.isNotEmpty() || (!shown.notesProcessed && shown.input.notes.isNotEmpty())) Button(enabled = prepared != null && connection.isConfigured(), modifier = Modifier.fillMaxWidth(), onClick = { openConsent(resume = true) }) { Text(when { stale -> tr("预览并分析最新内容", "Preview and analyze latest content"); shown != null && shown.input.analysisVersion < 3 -> tr("按新方式重新整理", "Reorganize with the new approach"); (shown?.errors?.isNotEmpty() == true || (shown != null && !shown.notesProcessed && shown.input.notes.isNotEmpty())) -> tr("预览并重试失败部分", "Preview and retry failed stages"); else -> tr("整理这节课", "Organize this class") }) }
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
                        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                            ReviewSectionHeading(tr("全课导览", "Whole-class guide"), number = "01")
                            Surface(color = palette.successBackground, shape = RoundedCornerShape(14.dp)) {
                                Text(outline.overview, Modifier.fillMaxWidth().padding(16.dp), style = MaterialTheme.typography.bodyLarge)
                            }
                            Text(tr("本课索引", "Your class index"), style = MaterialTheme.typography.titleLarge)
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                ReviewShortcut(tr("知识讲解", "Knowledge"), tr("${orderedUnits.size} 个知识块 ↓", "${orderedUnits.size} topics ↓"), ClassMemoryIcons.Notebook, Modifier.weight(1f)) { orderedUnits.firstOrNull()?.let { jumpToUnit(it.id) } }
                                ReviewShortcut(tr("复习清单", "Checklist"), tr("${shown.completedStepIndices.size} / ${shown.plan?.steps?.size ?: 0} 已完成 ↓", "${shown.completedStepIndices.size} / ${shown.plan?.steps?.size ?: 0} complete ↓"), ClassMemoryIcons.Check, Modifier.weight(1f), tone = 1) { jumpToSection("plan") }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                ReviewShortcut(tr("课后练习", "Practice"), tr("查看本课题目 ↓", "Class questions ↓"), ClassMemoryIcons.Edit, Modifier.weight(1f), tone = 2) { jumpToExercise() }
                                ReviewShortcut(tr("思维导图", "Knowledge map"), tr("独立展开查看 ↗", "Open map ↗"), ClassMemoryIcons.Share, Modifier.weight(1f)) { graphOpen = true }
                            }
                            ReviewPaper {
                                orderedUnits.take(3).forEachIndexed { index, unit ->
                                    TextButton(onClick = { jumpToUnit(unit.id) }, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
                                        ReviewBadge("K%02d".format(index + 1))
                                        Text(unit.title, Modifier.weight(1f).padding(horizontal = 10.dp), color = palette.text)
                                        Text("›")
                                    }
                                }
                                if (orderedUnits.size > 3) TextButton(onClick = { directoryOpen = true }) { Text(tr("查看全部 ${orderedUnits.size} 个知识块 ↓", "View all ${orderedUnits.size} topics ↓")) }
                            }
                            TextButton(onClick = { jumpToSection("whole-class-questions") }) { Text(tr("全课问答 ↓", "Whole-class questions ↓")) }
                        }
                    }
                }
                shown.plan?.let { plan ->
                    item(key = "plan") {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            ReviewSectionHeading(tr("复习清单", "Review checklist"), number = "02")
                            Text(plan.title, style = MaterialTheme.typography.titleLarge)
                            Text(plan.introduction, color = palette.muted)
                            Surface(color = palette.surfaceAlt, shape = RoundedCornerShape(14.dp)) {
                                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text(tr("${shown.completedStepIndices.size} / ${plan.steps.size} 项已完成", "${shown.completedStepIndices.size} / ${plan.steps.size} tasks complete"), style = MaterialTheme.typography.titleMedium, color = palette.primary)
                                    LinearProgressIndicator(progress = { if (plan.steps.isEmpty()) 0f else shown.completedStepIndices.size.toFloat() / plan.steps.size }, modifier = Modifier.fillMaxWidth(), color = palette.primary, trackColor = palette.border)
                                }
                            }
                            Text(tr("建议复习日期：${plan.suggestedDate}", "Suggested review date: ${plan.suggestedDate}"), style = MaterialTheme.typography.bodySmall, color = palette.muted)
                            plan.steps.forEachIndexed { index, step ->
                                ReviewPaper {
                                    ReviewBadge(tr("第 ${index + 1} 轮", "Round ${index + 1}"))
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(checked = index in shown.completedStepIndices, enabled = current == true && prepared != null && !running && !marking, onCheckedChange = { checked ->
                                            marking = true
                                            scope.launch {
                                                try { runtime.setStepCompleted(shown, index, checked) }
                                                catch (cancelled: CancellationException) { throw cancelled }
                                                catch (failure: Exception) { error = failure.message ?: tr("保存失败", "Could not save") }
                                                finally { marking = false }
                                            }
                                        })
                                        Text(step.task, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                                    }
                                    Text(tr("完成标准：", "Complete when: ") + step.completionCriteria, color = palette.muted, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                            Text(tr("勾选后自动保存；全部完成后资料仍会保留。", "Changes save automatically. Your material remains after every task is complete."), style = MaterialTheme.typography.bodySmall, color = palette.muted)
                        }
                    }
                }
                orderedUnits.forEach { unit ->
                item(key = "unit:${unit.id}", contentType = "knowledge") {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        ReviewSectionHeading(unit.title, number = "K%02d / %02d".format(orderedUnits.indexOf(unit) + 1, orderedUnits.size))
                        if (unit.markdown.isNotBlank()) ReviewContent(unit.markdown) else {
                            ReviewContent(listOfNotNull(unit.explanation, unit.conditions.takeIf { it.isNotBlank() }, unit.classroomFocus, unit.example?.takeIf { it.isNotBlank() }).joinToString("\n\n"))
                        }
                        shown.unitVersions[unit.id]?.takeIf { it.isNotEmpty() }?.let { versions ->
                            var showVersions by rememberSaveable(reviewSnapshotKey(shown), unit.id) { mutableStateOf(false) }
                            TextButton(onClick = { showVersions = !showVersions }) { Text(tr("查看 / 收起旧版讲解", "Show / hide earlier explanations")) }
                            if (showVersions) versions.forEachIndexed { index, markdown ->
                                Text(tr("旧版 ${index + 1}", "Earlier version ${index + 1}"), style = MaterialTheme.typography.titleSmall)
                                ReviewContent(markdown)
                            }
                        }
                        val source = unit.sourceIds.mapNotNull { sourcesById[it] }
                            .sortedWith(compareBy<ReviewSource>({ !it.isCurrent }, { it.startMs }, { it.sessionId }, { it.id })).firstOrNull()
                        val photos = unit.photoIds.mapNotNull { photosById[it] }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            source?.let { TextButton(onClick = { onSource(it) }, contentPadding = PaddingValues(0.dp)) { Text(tr("索引到出处 ↗", "Open source ↗")) } }
                            if (photos.isNotEmpty()) TextButton(onClick = { boardPhotos = photos }, contentPadding = PaddingValues(0.dp)) { Text(tr("查看板书", "View board")) }
                        }
                        HorizontalDivider(color = palette.border)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(tr("本知识块问答", "Questions on this topic"), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            TextButton(onClick = { askUnit(unit.id) }) { Text(tr("提问 +", "Ask +")) }
                        }
                        if (questionsByUnit[unit.id].isNullOrEmpty()) Text(tr("有疑问，随时从这里开始。", "Start a question here whenever you need."), color = palette.muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
                items(reviewQuestions(questionsByUnit[unit.id].orEmpty(), unit.id), key = { reviewQuestionKey(shown, it) }, contentType = { "question" }) { question ->
                    ReviewQuestionCard(reviewSnapshotKey(shown), question.threadId, question.turn) {
                        TextButton(onClick = { askUnit(unit.id, question.threadId) }) { Text(tr("追问此对话 →", "Continue this conversation →")) }
                    }
                }
                }
                item(key = "whole-class-questions") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        ReviewSectionHeading(tr("全课问答", "Whole-class questions"), number = "04")
                        if (questionsByUnit[null].isNullOrEmpty()) Text(tr("全课范围的问题保存在这里。", "Questions about the whole class are saved here."), color = palette.muted)
                        TextButton(onClick = { askUnit() }) { Text(tr("向整节课提问 +", "Ask about the whole class +")) }
                    }
                }
                items(reviewQuestions(questionsByUnit[null].orEmpty(), null), key = { reviewQuestionKey(shown, it) }, contentType = { "question" }) { question ->
                    ReviewQuestionCard(reviewSnapshotKey(shown), question.threadId, question.turn) {
                        TextButton(onClick = { askUnit(null, question.threadId) }) { Text(tr("追问此对话 →", "Continue this conversation →")) }
                    }
                }
                item(key = "exercises") {
                    ReviewExerciseHeader(
                        shown, connection, current == true && !running, exerciseBusy, exerciseError,
                        onGenerate = { requestExercise() },
                        onCancel = { exerciseJob?.cancel() },
                    )
                }
                items(shown.exercises, key = { "exercise:${it.id}" }, contentType = { "exercise" }) { exercise ->
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
            maxHeight = maxHeight * 0.88f,
            title = shown?.input?.title.orEmpty(),
            completed = shown?.completedStepIndices?.size ?: 0,
            total = shown?.plan?.steps?.size ?: 0,
            onGuide = { jumpToSection("outline") },
            onPlan = if (shown?.plan != null) ({ jumpToSection("plan") }) else null,
            onQuestions = { jumpToSection("whole-class-questions") },
            onGraph = { graphOpen = true },
            onTranscript = onTranscript,
            expanded = directoryOpen,
            onDismiss = { directoryOpen = false },
            onSelect = ::jumpToUnit,
            onExercises = if (shown == null) null else ({ jumpToExercise() }),
        )
        }
    }
    if (questionPanel && shown != null) ReviewQuestionDialog(shown, connection, runtime, current == true, onClose = { questionPanel = false }, initialTarget = questionTarget, initialThread = questionThread)
    if (graphOpen && shown != null) ReviewGraphDialog(shown, connection, runtime, current == true, onClose = { graphOpen = false }, onNode = { id -> graphOpen = false; jumpToUnit(id) })
    if (showHistory) Dialog(onDismissRequest = { showHistory = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().systemBarsPadding()) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Text(tr("旧版复习资料与问答", "Earlier review material and questions"), style = MaterialTheme.typography.titleLarge)
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    history.forEachIndexed { version, old ->
                        item { Text(tr("旧版 ${version + 1} · ${old.input.today}", "Earlier version ${version + 1} · ${old.input.today}"), style = MaterialTheme.typography.titleMedium) }
                        val oldUnits = old.outline?.topics?.mapNotNull { old.units[it.id] } ?: old.units.values.toList()
                        oldUnits.forEach { unit ->
                        item(key = "old:${old.input.sourceHash}:${unit.id}", contentType = "knowledge") {
                            ReviewBlock(unit.title) {
                                ReviewContent(unit.markdown.ifBlank { listOfNotNull(unit.explanation, unit.conditions, unit.classroomFocus, unit.example).joinToString("\n\n") })
                            }
                        }
                        items(reviewQuestions(old.qaThreads, unit.id), key = { reviewQuestionKey(old, it) }, contentType = { "question" }) { question ->
                            ReviewQuestionCard(reviewSnapshotKey(old), question.threadId, question.turn)
                        }
                        }
                        if (old.qaThreads.any { it.targetUnitId == null }) item { Text(tr("全课问答", "Whole-class questions"), style = MaterialTheme.typography.titleMedium) }
                        items(reviewQuestions(old.qaThreads, null), key = { reviewQuestionKey(old, it) }, contentType = { "question" }) { question ->
                            ReviewQuestionCard(reviewSnapshotKey(old), question.threadId, question.turn)
                        }
                        if (old.exercises.isNotEmpty()) item { Text(tr("课后练习", "After-class practice"), style = MaterialTheme.typography.titleMedium) }
                        items(old.exercises, key = { "old-exercise:${old.input.sourceHash}:${it.id}" }) { exercise ->
                            ReviewExerciseHistory(reviewSnapshotKey(old), exercise, old.exercises.indexOfFirst { it.id == exercise.id } + 1)
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
            runtime.start(input, connection)
            consent = null
        })
    }
    if (boardPhotos.isNotEmpty()) ReviewBoardPhotos(boardPhotos, repo, onClose = { boardPhotos = emptyList() })
}

@Composable
private fun ReviewBlock(title: String, body: @Composable ColumnScope.() -> Unit) {
    ReviewPaper {
        Text(title, style = MaterialTheme.typography.titleMedium)
        body()
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
    stage.startsWith("unit:") -> tr("正在整理知识讲解", "Preparing topic explanations")
    stage.startsWith("photo:") -> tr("正在分析板书", "Analyzing board photos")
    stage == "plan" -> tr("正在生成复习清单", "Building the review checklist")
    stage == "notes" -> tr("正在补充课堂疑问", "Adding classroom questions")
    else -> tr("正在整理课堂", "Organizing classroom material")
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
        Surface(Modifier.fillMaxSize().systemBarsPadding(), color = ClassMemoryColors.current.background) {
            Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("确认整理内容", "Confirm class material"), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    IconButton(onClick = onDismiss, enabled = !preparing) { Icon(ClassMemoryIcons.Close, tr("关闭预览", "Close preview")) }
                }
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
                    Text(tr("结合本课全部板书分析（上传 ${requestedPhotoCount} 张）", "Analyze all board photos from this class (upload ${requestedPhotoCount})"), Modifier.weight(1f))
                }
                if (preparing) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                    if (selection.includeRequested) TextButton(enabled = !preparing, onClick = { onIncludePhotos(false) }) {
                        Text(tr("仅用文字继续", "Continue with text only"))
                    }
                }
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
                    item {
                        ReviewPaper {
                            Text(input.title.orEmpty(), style = MaterialTheme.typography.titleMedium)
                            Text(tr("${input.sources.size} 段文字 · ${input.notes.size} 条疑问/注释 · ${input.photos.size} 张图片索引", "${input.sources.size} text segments · ${input.notes.size} notes · ${input.photos.size} photo indexes"), color = ClassMemoryColors.current.muted)
                            ReviewBadge(if (selection.includeRequested) tr("文字 + 所选板书", "Text + selected board photos") else tr("纯文字 · 不上传音频", "Text only · no audio upload"), ClassMemoryColors.current.successBackground, ClassMemoryColors.current.success)
                            Text(tr("下方可逐项查看本次会发送的资料。", "Review the material that will be sent below."), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    input.course?.let { c -> item {
                        ReviewBlock(tr("课程上下文", "Course context")) {
                            Text(c.name, style = MaterialTheme.typography.titleSmall)
                            Text("${tr("背景", "Background")}: ${c.background}\n${tr("目标", "Goals")}: ${c.goals}\n${tr("章节", "Chapters")}: ${c.chapters}\n${tr("术语", "Terminology")}: ${c.terminology}\n${tr("先修关系", "Prerequisites")}: ${c.prerequisites}\n${tr("来源", "Sources")}: ${c.sources}")
                        }
                    } }
                    items(input.sources, key = { "source:${it.id}" }) { s ->
                        ReviewBlock((if (s.isCurrent) tr("本课原文", "Current source") else tr("同课程历史原文", "Same-course history")) + " ${reviewTime(s.startMs)}–${reviewTime(s.endMs)}") {
                            Text(s.text)
                        }
                    }
                    items(input.notes, key = { "note:${it.id}" }) { n -> ReviewBlock((if (n.kind == "QUESTION") tr("课堂疑问", "Class question") else tr("课堂注释", "Class note")) + " · ${reviewTime(n.atMs)}") { Text(n.text) } }
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


@Composable
private fun ReviewFloatingAction(label: String, icon: ImageVector, emphasized: Boolean, onClick: () -> Unit) {
    val p = ClassMemoryColors.current
    Surface(onClick = onClick, modifier = Modifier.widthIn(min = 60.dp).heightIn(min = 60.dp),
        color = if (emphasized) p.primary else p.surface,
        contentColor = if (emphasized) p.onPrimary else p.primary,
        shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, p.primary), shadowElevation = 5.dp) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, Modifier.size(24.dp))
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}
