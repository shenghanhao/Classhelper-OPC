package com.classmemory.app.course

import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** User-confirmed course facts, separate from labels, hotwords and student mastery. */
data class CourseContext(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val background: String = "",
    val goals: String = "",
    val chapters: String = "",
    val terminology: String = "",
    val prerequisites: String = "",
    val sources: String = "",
    val revision: Long = 0,
    val updatedAt: Long = 0,
)

data class CourseContextState(
    val courses: List<CourseContext> = emptyList(),
    val assignments: Map<String, String> = emptyMap(),
) {
    fun courseFor(sessionId: String): CourseContext? = courses.find { it.id == assignments[sessionId] }
    fun contextKey(sessionId: String): String? = courseFor(sessionId)?.let { "${it.id}:${it.revision}" }
}

/** One atomic document avoids changing existing classroom databases. Never resets corrupt data. */
class CourseContextStore(private val file: File) {
    private val lock = Mutex()
    private val state = MutableStateFlow(CourseContextState())

    fun observe(): Flow<CourseContextState> = flow {
        snapshot()
        emitAll(state)
    }

    suspend fun snapshot(): CourseContextState = lock.withLock {
        withContext(Dispatchers.IO) { read().also { state.value = it } }
    }

    suspend fun save(draft: CourseContext): CourseContext = lock.withLock {
        withContext(Dispatchers.IO) {
            require(draft.id.isNotBlank() && draft.name.isNotBlank()) { "Course name is required / 请填写课程名称" }
            val current = read()
            val previous = current.courses.find { it.id == draft.id }
            check((previous?.revision ?: 0) == draft.revision) { "Course changed; reopen it / 课程已更新，请重新打开" }
            val saved = draft.copy(name = draft.name.trim(), revision = draft.revision + 1, updatedAt = System.currentTimeMillis())
            val courses = if (previous == null) current.courses + saved else current.courses.map { if (it.id == saved.id) saved else it }
            write(current.copy(courses = courses))
            saved
        }
    }

    suspend fun assign(sessionId: String, courseId: String?) = lock.withLock {
        withContext(Dispatchers.IO) {
            require(sessionId.isNotBlank())
            val current = read()
            require(courseId == null || current.courses.any { it.id == courseId }) { "Course not found / 课程不存在" }
            val links = current.assignments.toMutableMap()
            if (courseId == null) links.remove(sessionId) else links[sessionId] = courseId
            write(current.copy(assignments = links))
        }
    }

    private fun read(): CourseContextState {
        if (!file.exists()) return CourseContextState()
        val root = JSONObject(file.readText())
        check(root.getInt("schemaVersion") == 1) { "Unsupported course data version / 不支持的课程数据版本" }
        val array = root.getJSONArray("courses")
        val courses = (0 until array.length()).map { index ->
            val o = array.getJSONObject(index)
            CourseContext(o.getString("id"), o.getString("name"), o.getString("background"),
                o.getString("goals"), o.getString("chapters"), o.getString("terminology"),
                o.getString("prerequisites"), o.getString("sources"), o.getLong("revision"), o.getLong("updatedAt"))
        }
        check(courses.all { it.id.isNotBlank() && it.name.isNotBlank() && it.revision > 0 })
        check(courses.map { it.id }.distinct().size == courses.size)
        val links = root.getJSONObject("assignments")
        val assignments = links.keys().asSequence().associateWith { links.getString(it) }
        check(assignments.values.all { id -> courses.any { it.id == id } })
        return CourseContextState(courses, assignments)
    }

    private fun write(next: CourseContextState) {
        val root = JSONObject().put("schemaVersion", 1)
        root.put("courses", JSONArray().also { array -> next.courses.forEach { c ->
            array.put(JSONObject().put("id", c.id).put("name", c.name).put("background", c.background)
                .put("goals", c.goals).put("chapters", c.chapters).put("terminology", c.terminology)
                .put("prerequisites", c.prerequisites).put("sources", c.sources)
                .put("origin", "user_confirmed").put("revision", c.revision).put("updatedAt", c.updatedAt))
        } })
        root.put("assignments", JSONObject(next.assignments))
        val parent = requireNotNull(file.absoluteFile.parentFile)
        check(parent.isDirectory || parent.mkdirs()) { "Cannot create course storage / 无法创建课程存储目录" }
        val temporary = File.createTempFile("course-context-", ".tmp", parent)
        try {
            FileOutputStream(temporary).use { stream ->
                stream.write(root.toString().toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            check(temporary.renameTo(file)) { "Cannot save course context / 无法保存课程上下文" }
            state.value = next
        } finally { temporary.delete() }
    }
}
