package com.classmemory.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.classmemory.app.course.CourseContext
import com.classmemory.app.course.CourseContextState
import com.classmemory.app.data.Session
import com.classmemory.app.i18n.tr
import com.classmemory.app.repository.SessionRepository
import com.classmemory.app.ui.theme.ClassMemoryColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun CourseContextsScreen(repo: SessionRepository, onBack: () -> Unit, onOpenClass: (String) -> Unit) {
    val palette = ClassMemoryColors.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(CourseContextState()) }
    val sessions by repo.sessions().collectAsState(emptyList())
    var draft by remember { mutableStateOf<CourseContext?>(null) }
    var assignTarget by remember { mutableStateOf<Session?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    fun back() { if (draft != null) discard = true else onBack() }
    BackHandler { if (!busy) back() }
    LaunchedEffect(repo) {
        try { repo.courseContexts().collect { state = it; loaded = true } }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: tr("课程读取失败", "Couldn't load courses") }
    }
    Scaffold(
        containerColor = palette.background,
        topBar = {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { back() }, enabled = !busy) { Text(tr("返回", "Back")) }
                Text(if (draft == null) tr("课程上下文", "Course context") else tr("编辑课程", "Edit course"),
                    style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (draft != null) TextButton(enabled = !busy && draft!!.name.isNotBlank(), onClick = {
                    val candidate = draft ?: return@TextButton
                    busy = true
                    scope.launch {
                        try { repo.saveCourseContext(candidate); draft = null; error = null }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { error = e.message ?: tr("保存失败", "Save failed") }
                        finally { busy = false }
                    }
                }) { Text(if (busy) tr("保存中", "Saving") else tr("保存", "Save")) }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            error?.let { message -> item { Text(message, color = palette.error) } }
            val editing = draft
            if (editing != null) {
                item { Text(tr("保存即确认以下内容。课程资料与个人掌握情况分开记录。", "Save to confirm these course facts. They are separate from your learning progress."), color = palette.muted) }
                item { ContextField(tr("课程名称", "Course name"), editing.name, !busy) { draft = editing.copy(name = it) } }
                item { ContextField(tr("课程背景", "Background"), editing.background, !busy) { draft = editing.copy(background = it) } }
                item { ContextField(tr("学习目标", "Learning goals"), editing.goals, !busy) { draft = editing.copy(goals = it) } }
                item { ContextField(tr("章节结构", "Chapters"), editing.chapters, !busy) { draft = editing.copy(chapters = it) } }
                item { ContextField(tr("术语与符号约定", "Terms and symbols"), editing.terminology, !busy) { draft = editing.copy(terminology = it) } }
                item { ContextField(tr("先修知识与关系", "Prerequisites"), editing.prerequisites, !busy) { draft = editing.copy(prerequisites = it) } }
                item { ContextField(tr("资料来源（教材、章节或课堂时间）", "Sources (textbook, chapter or class time)"), editing.sources, !busy) { draft = editing.copy(sources = it) } }
            } else {
                item {
                    Button(onClick = { draft = CourseContext() }, enabled = loaded && !busy, modifier = Modifier.fillMaxWidth()) {
                        Text(tr("新建课程", "New course"))
                    }
                }
                if (!loaded && error == null) item { Text(tr("读取中…", "Loading…")) }
                if (loaded && state.courses.isEmpty()) item { Text(tr("先新建课程，再将下方课堂归入课程。原有分类标签与热词库保持独立。", "Create a course, then assign classes below. Labels and hotword libraries remain separate.")) }
                items(state.courses, key = { "course:${it.id}" }) { course ->
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(course.name, style = MaterialTheme.typography.titleLarge)
                            Text(tr("版本 ${course.revision} · 用户确认", "Revision ${course.revision} · User confirmed"), color = palette.muted)
                            listOf(tr("背景", "Background") to course.background, tr("目标", "Goals") to course.goals,
                                tr("章节", "Chapters") to course.chapters, tr("术语与符号", "Terms and symbols") to course.terminology,
                                tr("先修关系", "Prerequisites") to course.prerequisites).forEach { (label, content) ->
                                if (content.isNotBlank()) Text("$label\n$content")
                            }
                            Text(tr("来源：", "Sources: ") + course.sources.ifBlank { tr("用户手动填写，尚未补充资料来源", "Manually entered; no supporting source added yet") })
                            val linked = sessions.filter { state.assignments[it.id] == course.id }
                            Text(tr("关联课堂 ${linked.size} 节", "${linked.size} linked classes"), style = MaterialTheme.typography.titleSmall)
                            linked.forEach { session ->
                                TextButton(onClick = { onOpenClass(session.id) }) { Text(classLabel(session)) }
                            }
                            TextButton(onClick = { draft = course }, enabled = !busy) { Text(tr("查看与编辑", "View and edit")) }
                        }
                    }
                }
                item { Text(tr("课堂归属", "Class assignments"), style = MaterialTheme.typography.titleLarge) }
                item { Text(tr("每节课堂归入一门课程；改名不会改变关联。修改课程资料后，后续分析会重新检查上下文版本。", "Assign each class to one course. Renaming preserves links; analysis checks the context revision after edits."), color = palette.muted) }
                items(sessions, key = { "session:${it.id}" }) { session ->
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(classLabel(session))
                            Text(state.courseFor(session.id)?.name ?: tr("未归入课程", "Unassigned"), color = palette.primary)
                            Row {
                                TextButton(onClick = { assignTarget = session }, enabled = loaded && !busy) { Text(tr("选择课程", "Assign course")) }
                                TextButton(onClick = { onOpenClass(session.id) }) { Text(tr("查看原文", "Open class")) }
                            }
                        }
                    }
                }
            }
        }
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text(tr("放弃未保存修改？", "Discard unsaved changes?")) },
        confirmButton = { TextButton(onClick = { draft = null; discard = false }) { Text(tr("放弃", "Discard")) } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text(tr("继续编辑", "Keep editing")) } })
    assignTarget?.let { target ->
        fun assign(courseId: String?) {
            busy = true
            scope.launch {
                try { repo.assignCourse(target.id, courseId); assignTarget = null; error = null }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = e.message ?: tr("归属保存失败", "Couldn't assign class"); assignTarget = null }
                finally { busy = false }
            }
        }
        AlertDialog(onDismissRequest = { if (!busy) assignTarget = null }, title = { Text(tr("选择课程", "Assign course")) },
            text = { LazyColumn(Modifier.heightIn(max = 400.dp)) {
                item { TextButton(onClick = { assign(null) }, enabled = !busy) { Text(tr("不归入课程", "Unassigned")) } }
                items(state.courses, key = { it.id }) { course ->
                    TextButton(onClick = { assign(course.id) }, enabled = !busy) { Text(course.name) }
                }
            } },
            confirmButton = { TextButton(onClick = { assignTarget = null }, enabled = !busy) { Text(tr("取消", "Cancel")) } })
    }
}

@Composable
private fun ContextField(label: String, value: String, enabled: Boolean, onChange: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = onChange, enabled = enabled, label = { Text(label) },
        modifier = Modifier.fillMaxWidth(), minLines = 2)
}

private fun classLabel(session: Session): String =
    "${session.title?.takeIf { it.isNotBlank() } ?: tr("未命名课堂", "Untitled class")} · " +
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(session.startedAt))
