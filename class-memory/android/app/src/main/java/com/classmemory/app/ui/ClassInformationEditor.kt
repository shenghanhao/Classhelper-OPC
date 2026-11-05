package com.classmemory.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.classmemory.app.course.CourseContextState
import com.classmemory.app.data.Session
import com.classmemory.app.data.displayCourseType
import com.classmemory.app.i18n.tr
import com.classmemory.app.repository.SessionRepository
import com.classmemory.app.ui.icons.ClassMemoryIcons
import com.classmemory.app.ui.theme.ClassMemoryColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Metadata and course association stay separate; course facts have their own explicit save. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ClassInformationEditor(repo: SessionRepository, session: Session, onClose: () -> Unit) {
    val palette = ClassMemoryColors.current
    val scope = rememberCoroutineScope()
    val types by repo.courseTypes().collectAsState(emptyList())
    var contexts by remember { mutableStateOf(CourseContextState()) }
    var loaded by remember { mutableStateOf(false) }
    var title by remember(session.id) { mutableStateOf(session.title.orEmpty()) }
    var type by remember(session.id) { mutableStateOf(session.courseType) }
    var courseId by remember(session.id) { mutableStateOf<String?>(null) }
    var originalCourseId by remember(session.id) { mutableStateOf<String?>(null) }
    var typeExpanded by remember { mutableStateOf(false) }
    var courseExpanded by remember { mutableStateOf(false) }
    var editingCourse by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var discard by remember { mutableStateOf(false) }
    var detailsExpanded by remember { mutableStateOf(false) }
    val dirty = title != session.title.orEmpty() || type != session.courseType || (loaded && courseId != originalCourseId)
    fun close() { if (!saving) { if (dirty) discard = true else onClose() } }
    fun save() {
        if (saving || !loaded || title.isBlank()) return
        saving = true; error = null
        scope.launch {
            try {
                repo.updateMetadata(session.id, title, type)
                if (courseId != originalCourseId) repo.assignCourse(session.id, courseId)
                onClose()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = tr("部分信息可能已保存，请检查名称和关联课程后重试。", "Some details may have been saved. Check the name and course link, then retry.") }
            finally { saving = false }
        }
    }
    LaunchedEffect(repo, session.id) {
        try {
            repo.courseContexts().collect { value ->
                contexts = value
                if (!loaded) {
                    originalCourseId = value.assignments[session.id]
                    courseId = originalCourseId
                    loaded = true
                }
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = tr("课程背景读取失败，请返回后重试。", "Couldn't load course context. Close this page and try again.") }
    }
    Dialog(onDismissRequest = ::close, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        if (editingCourse) {
            CourseContextsScreen(repo, onBack = { editingCourse = false }, onOpenClass = {}, initialCourseId = courseId,
                createNew = contexts.courses.none { it.id == courseId }, onSaved = { savedId -> courseId = savedId; editingCourse = false })
        } else {
            BackHandler { close() }
            Scaffold(containerColor = palette.background, topBar = {
                TopAppBar(title = { Text(tr("课堂信息", "Class information")) },
                    navigationIcon = { IconButton(onClick = ::close, enabled = !saving) { Icon(ClassMemoryIcons.Back, tr("返回", "Back")) } },
                    actions = { TextButton(onClick = ::save, enabled = loaded && !saving && title.isNotBlank()) { Text(tr("保存", "Save")) } },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = palette.background))
            }, bottomBar = {
                Button(onClick = ::save, enabled = loaded && !saving && title.isNotBlank(), shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(18.dp).heightIn(min = 50.dp)) {
                    Text(if (saving) tr("保存中…", "Saving…") else tr("保存课堂信息", "Save class information"))
                }
            }) { padding ->
                LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    item { Text(tr("名称、类型与课程背景", "Name, type and course context"), color = palette.muted, style = MaterialTheme.typography.bodyMedium) }
                    error?.let { message -> item { Text(message, color = palette.error) } }
                    item {
                        OutlinedTextField(title, { if (it.length <= 60) title = it }, modifier = Modifier.fillMaxWidth(), enabled = !saving,
                            label = { Text(tr("课堂名称", "Class name")) }, supportingText = { Text(tr("最多 60 个字符", "Up to 60 characters")) }, singleLine = true)
                    }
                    item {
                        ExposedDropdownMenuBox(typeExpanded, { if (!saving) typeExpanded = it }) {
                            OutlinedTextField(type?.let(::displayCourseType) ?: tr("未分类", "Uncategorized"), {}, readOnly = true, enabled = !saving,
                                label = { Text(tr("课堂类型", "Class type")) }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(typeExpanded) },
                                modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth())
                            ExposedDropdownMenu(typeExpanded, { typeExpanded = false }) {
                                DropdownMenuItem(text = { Text(tr("未分类", "Uncategorized")) }, onClick = { type = null; typeExpanded = false })
                                types.forEach { entry -> DropdownMenuItem(text = { Text(displayCourseType(entry.name)) }, onClick = { type = entry.name; typeExpanded = false }) }
                            }
                        }
                    }
                    item { HorizontalDivider(color = palette.border) }
                    item {
                        Text(tr("关联课程", "Linked course"), style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(10.dp))
                        ExposedDropdownMenuBox(courseExpanded, { if (!saving && loaded) courseExpanded = it }) {
                            OutlinedTextField(contexts.courses.firstOrNull { it.id == courseId }?.name ?: tr("未关联", "Not linked"), {}, readOnly = true, enabled = loaded && !saving,
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(courseExpanded) }, modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth())
                            ExposedDropdownMenu(courseExpanded, { courseExpanded = false }) {
                                DropdownMenuItem(text = { Text(tr("不关联课程", "No linked course")) }, onClick = { courseId = null; courseExpanded = false })
                                contexts.courses.forEach { course -> DropdownMenuItem(text = { Text(course.name) }, onClick = { courseId = course.id; courseExpanded = false }) }
                            }
                        }
                        Text(tr("课堂类型与关联课程分别设置", "Class labels and course links are separate"), style = MaterialTheme.typography.bodySmall, color = palette.muted, modifier = Modifier.padding(top = 7.dp))
                    }
                    item {
                        val course = contexts.courses.firstOrNull { it.id == courseId }
                        OutlinedCard(Modifier.fillMaxWidth(), colors = CardDefaults.outlinedCardColors(containerColor = palette.surface), border = BorderStroke(1.dp, palette.border)) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(tr("课程背景", "Course context"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                    Text(tr("课程共用", "Shared"), color = palette.primary, style = MaterialTheme.typography.labelSmall)
                                }
                                Text(course?.background?.takeIf { it.isNotBlank() } ?: tr("为这门课程补充背景，让复习讲解更贴近课堂。", "Add course background for more relevant review notes."), style = MaterialTheme.typography.bodyMedium, color = palette.muted)
                                TextButton(onClick = { editingCourse = true }, enabled = loaded && !saving, contentPadding = PaddingValues(0.dp)) {
                                    Text(if (course == null) tr("新建课程背景  →", "Create course context  →") else tr("查看与编辑  →", "View and edit  →"))
                                }
                            }
                        }
                    }
                    val course = contexts.courses.firstOrNull { it.id == courseId }
                    if (course != null) {
                        item {
                            OutlinedCard(onClick = { detailsExpanded = !detailsExpanded }, modifier = Modifier.fillMaxWidth(), colors = CardDefaults.outlinedCardColors(containerColor = palette.surface)) {
                                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(tr("学习目标、章节与术语", "Goals, chapters and terminology"), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                                    Text(if (detailsExpanded) "⌃" else "⌄", color = palette.primary)
                                }
                            }
                        }
                        if (detailsExpanded) item {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                listOf(tr("学习目标", "Learning goals") to course.goals, tr("章节结构", "Chapters") to course.chapters,
                                    tr("术语与符号", "Terms and symbols") to course.terminology, tr("先修关系", "Prerequisites") to course.prerequisites,
                                    tr("资料来源", "Sources") to course.sources).forEach { (label, value) ->
                                    Text(label, style = MaterialTheme.typography.titleSmall)
                                    Text(value.ifBlank { tr("尚未填写", "Not added yet") }, style = MaterialTheme.typography.bodyMedium, color = palette.muted)
                                }
                            }
                        }
                    }
                    item { Text(tr("课程资料在编辑页单独保存；此处保存课堂名称、类型与关联。", "Course facts are saved separately in their editor; this page saves the class name, type and link."), color = palette.muted, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text(tr("放弃未保存的课堂信息？", "Discard unsaved class information?")) },
            confirmButton = { TextButton(onClick = onClose) { Text(tr("放弃", "Discard")) } },
            dismissButton = { TextButton(onClick = { discard = false }) { Text(tr("继续编辑", "Keep editing")) } })
    }
}
