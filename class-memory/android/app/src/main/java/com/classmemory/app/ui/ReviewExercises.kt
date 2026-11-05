package com.classmemory.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import com.classmemory.app.ui.icons.ClassMemoryIcons
import com.classmemory.app.ui.theme.ClassMemoryColors
import androidx.compose.ui.unit.dp
import com.classmemory.app.i18n.tr
import com.classmemory.app.review.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal fun canSubmitExerciseAnswer(exercise: ReviewExercise, answer: String): Boolean {
    val value = answer.trim()
    return value.isNotEmpty() && exercise.attempts.none { it.answer == value && it.status in setOf("graded", "pending", "failed") }
}

@Composable
internal fun ReviewExerciseHeader(
    run: ReviewRun,
    connection: ReviewConnection,
    current: Boolean,
    busy: Boolean,
    error: String?,
    onGenerate: () -> Unit,
    onCancel: () -> Unit,
) {
    var confirming by remember { mutableStateOf(false) }
    val palette = ClassMemoryColors.current
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ReviewSectionHeading(tr("课后练习", "After-class practice"), number = "05")
        if (run.exercises.isEmpty()) {
            val count = reviewExerciseTargetCount(run.units.size)
            ReviewPaper {
                Icon(ClassMemoryIcons.Edit, null, Modifier.align(Alignment.CenterHorizontally).size(38.dp), tint = palette.primary)
                Text(tr("尚未生成练习", "Practice is ready to begin"), style = MaterialTheme.typography.titleLarge)
                Text(tr("本课 ${run.units.size} 个知识块，生成约 $count 道题。选择与简答搭配，用自己的文字说明答案。", "${run.units.size} topics, about $count questions. A mix of multiple-choice and short answers, explained in your own words."), color = palette.muted)
            }
            if (run.plan == null) Text(tr("完成课堂复盘后可生成练习。", "Complete the classroom review to generate practice questions."))
            Button(
                enabled = current && run.plan != null && connection.isConfigured() && connection.mode == ReviewConnectionMode.DIRECT_DEEPSEEK && !busy,
                onClick = { confirming = true }, modifier = Modifier.fillMaxWidth(),
            ) { Text(tr("生成课后练习", "Generate practice questions")) }
        }
        if (run.exercises.isNotEmpty()) Text(tr("共 ${run.exercises.size} 道题 · 默认展开，可逐题收起", "${run.exercises.size} questions · expand or collapse each one"), color = palette.muted, style = MaterialTheme.typography.bodySmall)
        if (connection.mode != ReviewConnectionMode.DIRECT_DEEPSEEK) Text(tr("生成与评阅练习需要在服务设置中选择手机直连 DeepSeek。", "Select a direct DeepSeek connection in service settings to generate and grade practice."))
        if (!current) Text(tr("当前资料已过期，可阅读已有练习；更新复盘后再提交。", "This material is outdated. Existing practice remains readable; update the review before submitting."))
        if (busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            TextButton(onClick = onCancel) { Text(tr("取消本次请求", "Cancel request")) }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
    if (confirming) ExerciseSendConsent(
        submitting = false,
        onDismiss = { confirming = false },
        onConfirm = { confirming = false; onGenerate() },
        enabled = current && !busy && run.plan != null && connection.isConfigured() && connection.mode == ReviewConnectionMode.DIRECT_DEEPSEEK,
    )
}

@Composable
internal fun ReviewExerciseCard(
    run: ReviewRun,
    exercise: ReviewExercise,
    number: Int,
    runtime: ReviewRuntime,
    ownerScope: CoroutineScope,
    enabled: Boolean,
    busy: Boolean,
    onSubmit: (ReviewExercise, String, String?) -> Unit,
    onCancel: () -> Unit,
) {
    val draftKey = "${run.input.sourceHash}:${exercise.id}"
    var expanded by rememberSaveable(reviewDisclosureKey(reviewSnapshotKey(run), "exercise", exercise.id)) { mutableStateOf(true) }
    var answer by remember(draftKey) { mutableStateOf("") }
    var loaded by remember(draftKey) { mutableStateOf(false) }
    var draftError by remember(draftKey) { mutableStateOf<String?>(null) }
    var draftSaved by remember(draftKey) { mutableStateOf(false) }
    var confirmingAnswer by remember(draftKey) { mutableStateOf<String?>(null) }
    var retryId by remember(draftKey) { mutableStateOf<String?>(null) }
    var saveJob by remember(draftKey) { mutableStateOf<Job?>(null) }
    val latestAnswer by rememberUpdatedState(answer)
    val latestLoaded by rememberUpdatedState(loaded)
    val latestSaveJob by rememberUpdatedState(saveJob)

    LaunchedEffect(draftKey) {
        try {
            answer = runtime.store.loadExerciseDraft(run.input.sessionId, run.input.sourceHash, exercise.id).orEmpty()
            loaded = true
            draftSaved = answer.isNotBlank()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { draftError = tr("无法读取作答草稿，请重新打开此页面。", "Could not load your draft. Reopen this page.") }
    }
    DisposableEffect(draftKey) {
        onDispose {
            latestSaveJob?.cancel()
            if (latestLoaded) {
                val finalAnswer = latestAnswer
                ownerScope.launch(NonCancellable) {
                    runCatching { runtime.store.saveExerciseDraft(run.input.sessionId, run.input.sourceHash, exercise.id, finalAnswer) }
                }
            }
        }
    }
    Surface(Modifier.fillMaxWidth(), color = ClassMemoryColors.current.surface,
        shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, ClassMemoryColors.current.border)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ReviewDisclosureHeader(exerciseTitle(exercise, number) + " · " + exerciseKindLabel(exercise.kind), expanded) { expanded = !expanded }
            if (!expanded && answer.isNotBlank()) Text(tr("已收起 · 草稿保留", "Collapsed · draft retained"), color = ClassMemoryColors.current.muted, style = MaterialTheme.typography.bodySmall)
            if (expanded) {
            val unitIndex = orderedReviewUnits(run).indexOfFirst { it.id == exercise.unitId }
            run.units[exercise.unitId]?.let { ReviewBadge("K%02d · %s".format(unitIndex + 1, it.title)) }
            if (exercise.parentId != null) Text(tr("针对练习 · 来自练习 ${run.exercises.indexOfFirst { it.id == exercise.parentId } + 1}", "Follow-up · from question ${run.exercises.indexOfFirst { it.id == exercise.parentId } + 1}"), style = MaterialTheme.typography.bodySmall, color = ClassMemoryColors.current.warning)
            ReviewExerciseQuestion(exercise)
            ReviewExerciseAttempts(reviewSnapshotKey(run), exercise, onRetry = { attempt ->
                retryId = attempt.id
                confirmingAnswer = attempt.answer
            }, retryEnabled = enabled && !busy)
            OutlinedTextField(
                value = answer,
                onValueChange = { value ->
                    answer = value
                    draftError = null
                    draftSaved = false
                    saveJob?.cancel()
                    saveJob = ownerScope.launch {
                        delay(600)
                        try {
                            runtime.store.saveExerciseDraft(run.input.sessionId, run.input.sourceHash, exercise.id, value)
                            draftSaved = true
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { draftError = tr("草稿保存失败，请稍后重试。", "Could not save your draft. Try again shortly.") }
                    }
                },
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                enabled = enabled && !busy && loaded,
                minLines = 3,
                maxLines = 8,
                label = { Text(tr("我的作答", "My answer")) },
                placeholder = { Text(if (exercise.kind == "MCQ") tr("为什么选它、其他选项为什么不对？请写出你的判断和理由。", "Why choose it, and why are the other options wrong? Explain your reasoning.") else tr("写出答案和思考过程。", "Write your answer and reasoning.")) },
            )
            if (draftSaved && answer.isNotBlank()) Text(tr("✓ 草稿已保存在本机", "✓ Draft saved on this device"), color = ClassMemoryColors.current.success, style = MaterialTheme.typography.bodySmall)
            if (exercise.kind == "MCQ") Text(tr("请说明选择理由，以及其他选项为什么不对。", "Explain your choice and why the other options are wrong."), color = ClassMemoryColors.current.muted, style = MaterialTheme.typography.bodySmall)
            draftError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (busy) TextButton(onClick = onCancel) { Text(tr("取消当前请求", "Cancel current request")) }
            if (answer.isNotBlank() && !canSubmitExerciseAnswer(exercise, answer)) Text(tr("这份作答已保存，可查看评阅或使用对应的重试入口。", "This answer is saved. View its feedback or use its retry action."))
            Button(
                enabled = enabled && !busy && loaded && canSubmitExerciseAnswer(exercise, answer),
                onClick = { retryId = null; confirmingAnswer = answer.trim() }, modifier = Modifier.fillMaxWidth(),
            ) { Text(tr("提交作答", "Submit answer")) }
            Text(tr("提交前确认发送内容", "Confirm what is sent before submitting"), color = ClassMemoryColors.current.muted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    confirmingAnswer?.let { submitted ->
        ExerciseSendConsent(
            submitting = true,
            enabled = enabled && !busy,
            onDismiss = { confirmingAnswer = null },
            onConfirm = { confirmingAnswer = null; onSubmit(exercise, submitted, retryId) },
        )
    }
}

@Composable
internal fun ReviewExerciseHistory(snapshotKey: String, exercise: ReviewExercise, number: Int) {
    var expanded by rememberSaveable(reviewDisclosureKey(snapshotKey, "exercise", exercise.id)) { mutableStateOf(true) }
    Surface(Modifier.fillMaxWidth(), color = ClassMemoryColors.current.surface,
        shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, ClassMemoryColors.current.border)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ReviewDisclosureHeader(exerciseTitle(exercise, number), expanded) { expanded = !expanded }
            if (expanded) {
                ReviewExerciseQuestion(exercise)
                ReviewExerciseAttempts(snapshotKey, exercise)
            }
        }
    }
}

@Composable
private fun exerciseTitle(exercise: ReviewExercise, number: Int): String =
    (if (exercise.parentId == null) tr("练习", "Question") else tr("针对练习", "Follow-up")) + " $number"

private fun exerciseKindLabel(kind: String): String = if (kind == "MCQ") tr("选择题", "Multiple choice") else tr("简答题", "Short answer")

@Composable
private fun ReviewExerciseQuestion(exercise: ReviewExercise) {
    // Keep one cached rich-text renderer per question; options are read-only quoted cards.
    val content = remember(exercise.question, exercise.options) {
        (listOf(exercise.question) + exercise.options.map { "> " + it.replace("\n", "\n> ") }).joinToString("\n\n")
    }
    ReviewContent(content)
}

@Composable
private fun ReviewExerciseAttempts(
    snapshotKey: String,
    exercise: ReviewExercise,
    onRetry: ((ReviewExerciseAttempt) -> Unit)? = null,
    retryEnabled: Boolean = false,
) {
    var showEarlier by rememberSaveable(reviewDisclosureKey(snapshotKey, "attempts", exercise.id)) { mutableStateOf(false) }
    if (exercise.attempts.size > 1) TextButton(onClick = { showEarlier = !showEarlier }) {
        Text(if (showEarlier) tr("收起历史作答", "Hide earlier answers") else tr("查看历史作答", "Show earlier answers"))
    }
    val attempts = if (showEarlier) exercise.attempts else exercise.attempts.takeLast(1)
    attempts.forEach { attempt ->
        Text(tr("我的作答", "My answer"), style = MaterialTheme.typography.titleSmall)
        Surface(shape = RoundedCornerShape(12.dp), color = ClassMemoryColors.current.surfaceAlt) {
            Text(attempt.answer, Modifier.fillMaxWidth().padding(12.dp))
        }
        if (attempt.status == "graded") {
            ReviewBadge(when (attempt.verdict) {
                "correct" -> tr("正确", "Correct")
                "partial" -> tr("部分正确", "Partially correct")
                "incorrect" -> tr("需要修正", "Needs correction")
                else -> tr("需要进一步说明", "Needs clarification")
            }, if (attempt.verdict == "correct") ClassMemoryColors.current.successBackground else ClassMemoryColors.current.warningBackground,
                if (attempt.verdict == "correct") ClassMemoryColors.current.success else ClassMemoryColors.current.warning)
            Text(tr("评阅分析", "Feedback"), style = MaterialTheme.typography.titleSmall)
            ReviewContent(attempt.feedback.orEmpty())
            Text(tr("正确思路与讲解", "Explanation and approach"), style = MaterialTheme.typography.titleSmall)
            ReviewContent(attempt.reasoning.orEmpty())
            ReviewBadge(tr("✓ 作答与反馈已保存到对应知识块问答", "✓ Answer and feedback saved with this topic"), ClassMemoryColors.current.successBackground, ClassMemoryColors.current.success)
        } else {
            Text(if (attempt.status == "pending") tr("等待评阅或上次提交已中断", "Awaiting review, or the previous submission was interrupted") else tr("本次评阅失败，作答已保存。", "Grading failed. Your answer is saved."))
            if (onRetry != null) TextButton(enabled = retryEnabled, onClick = { onRetry(attempt) }) {
                Text(tr("重试此作答", "Retry this answer"))
            }
        }
    }
}

@Composable
private fun ExerciseSendConsent(submitting: Boolean, enabled: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (submitting) tr("提交给 DeepSeek 评阅？", "Submit to DeepSeek for grading?") else tr("让 DeepSeek 生成练习？", "Generate practice with DeepSeek?")) },
        text = {
            Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("将发送本课完整文字、课程上下文、疑问与注释、知识块和已保存的板书分析文字。", "This sends the full classroom text, course context, questions and notes, knowledge units, and saved board-photo observations."))
                if (submitting) Text(tr("同时发送本题和此次作答，用于解释正确知识和作答差距。", "The question and this answer are included to explain the correct approach and gaps in your reasoning."))
                Text(tr("本次仅发送文字，调用费用由你的 DeepSeek 账户承担。", "This request sends text and is billed to your DeepSeek account."))
            }
        },
        confirmButton = { Button(onClick = onConfirm, enabled = enabled) { Text(tr("同意并发送", "Agree and send")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("取消", "Cancel")) } },
    )
}
