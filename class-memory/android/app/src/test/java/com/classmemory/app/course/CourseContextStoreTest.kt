package com.classmemory.app.course

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CourseContextStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun file() = File(temporary.root, "courses.json")

    @Test fun renamePreservesTwoClassLinksAndSurvivesReopening() = runBlocking {
        val store = CourseContextStore(file())
        val first = store.save(CourseContext(name = "Mechanics", terminology = "a = acceleration", sources = "User supplied textbook chapter 1"))
        store.assign("class-1", first.id)
        store.assign("class-2", first.id)
        val oldKey = store.snapshot().contextKey("class-1")
        val renamed = store.save(first.copy(name = "Mechanics I"))
        val reopened = CourseContextStore(file()).snapshot()
        assertEquals(renamed.id, reopened.courseFor("class-1")!!.id)
        assertEquals(renamed, reopened.courseFor("class-2"))
        assertNotEquals(oldKey, reopened.contextKey("class-1"))
        assertEquals("User supplied textbook chapter 1", reopened.courseFor("class-1")!!.sources)
    }

    @Test fun sameNamedCoursesRemainIndependentAndReassignmentIsExplicit() = runBlocking {
        val store = CourseContextStore(file())
        val a = store.save(CourseContext(name = "Course", terminology = "alpha"))
        val b = store.save(CourseContext(name = "Course", terminology = "beta"))
        store.assign("first", a.id)
        store.assign("second", b.id)
        assertEquals("alpha", store.snapshot().courseFor("first")!!.terminology)
        assertEquals("beta", store.snapshot().courseFor("second")!!.terminology)
        store.assign("first", b.id)
        store.assign("second", null)
        assertEquals(b.id, store.snapshot().courseFor("first")!!.id)
        assertNull(store.snapshot().courseFor("second"))
    }

    @Test fun staleEditsCannotOverwriteNewerFacts() = runBlocking {
        val store = CourseContextStore(file())
        val first = store.save(CourseContext(name = "Course"))
        store.save(first.copy(goals = "Updated"))
        val failure = runCatching { store.save(first.copy(goals = "Stale")) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals("Updated", store.snapshot().courses.single().goals)
    }

    @Test fun corruptDocumentIsPreservedAndNeverSilentlyReset() = runBlocking {
        file().writeText("{broken")
        val before = file().readText()
        assertTrue(runCatching { CourseContextStore(file()).save(CourseContext(name = "Course")) }.isFailure)
        assertEquals(before, file().readText())
    }

    @Test fun unknownCourseAndEmptyNameDoNotWrite() = runBlocking {
        val store = CourseContextStore(file())
        assertTrue(runCatching { store.assign("class", "missing") }.isFailure)
        assertTrue(runCatching { store.save(CourseContext(name = "  ")) }.isFailure)
        assertFalse(file().exists())
    }

    @Test fun longContextIsStoredWithoutTruncationOrTemporaryFiles() = runBlocking {
        val store = CourseContextStore(file())
        val text = "A course with detailed sources\n".repeat(5_000)
        val saved = store.save(CourseContext(name = "Course", background = text, sources = text))
        assertEquals(text, CourseContextStore(file()).snapshot().courses.single().background)
        assertEquals(text, CourseContextStore(file()).snapshot().courses.single().sources)
        assertEquals(saved.id, store.snapshot().courses.single().id)
        assertTrue(temporary.root.listFiles()!!.none { it.name.endsWith(".tmp") })
    }
}
