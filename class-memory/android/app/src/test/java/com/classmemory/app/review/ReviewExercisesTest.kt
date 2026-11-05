package com.classmemory.app.review

import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReviewExercisesTest {
    @Before fun setup() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun reset() { Dispatchers.resetMain() }
    private fun run(): ReviewRun {
        val raw = ReviewInput("class", "", "zh", null, "2026-11-05", null, listOf(ReviewSource("s", "class", "seg", 0, 1, "原文", true)), emptyList(), emptyList(), 3)
        val input = raw.copy(sourceHash = raw.contentHash())
        val outline = ReviewOutline("导览", listOf(ReviewSection("part", "部分", listOf("s"))), listOf(ReviewTopic("unit", "主题", listOf("part"), listOf("s"))))
        val unit = ReviewUnit("unit", "主题", "解释", "", "重点", null, listOf("s"), emptyList(), listOf("part"), "正文")
        val plan = ReviewPlan("复习", "介绍", "2026-11-05", listOf(ReviewStep("回顾", "复习", listOf("unit"))))
        return ReviewRun(input, outline, mapOf("unit" to unit), plan, updatedAt = 1)
    }
    private open class Gateway : ReviewGateway {
        var fails = false; var grades = 0
        override suspend fun outline(input: ReviewInput): ReviewOutline = error("unexpected")
        override suspend fun unit(input: ReviewInput, outline: ReviewOutline, topicId: String): ReviewUnit = error("unexpected")
        override suspend fun plan(input: ReviewInput, outline: ReviewOutline, units: List<ReviewUnit>): ReviewPlan = error("unexpected")
        override suspend fun generateExercises(input: ReviewInput, outline: ReviewOutline, units: List<ReviewUnit>) = listOf(ReviewExerciseDraft("unit", "MCQ", "选择并解释", listOf("A. 一", "B. 二")))
        override suspend fun gradeExercise(input: ReviewInput, outline: ReviewOutline, units: List<ReviewUnit>, exercise: ReviewExercise, answer: String, allowFollowUp: Boolean): ReviewExerciseGrade {
            grades++; if (fails) error("offline")
            return ReviewExerciseGrade("partial", "反馈", "正确思路", listOf("s"), if (allowFollowUp) ReviewExerciseDraft("unit", "SHORT", "再解释", emptyList()) else null)
        }
    }
    @Test fun generateGradePersistQaAppendAndConsumeNotice() = runBlocking {
        val dir = Files.createTempDirectory("exercises").toFile()
        try {
            val base = run(); val store = ReviewStore(dir); store.save(base); val gateway = Gateway(); val runtime = ReviewRuntime(store, { true }, { gateway })
            val generated = runtime.generateExercises(base, ReviewConnection()); assertEquals(1, generated.exercises.size)
            store.saveExerciseDraft("class", base.input.sourceHash, generated.exercises.single().id, "未完成的回答")
            assertEquals("未完成的回答", ReviewStore(dir).loadExerciseDraft("class", base.input.sourceHash, generated.exercises.single().id))
            store.saveExerciseDraft("class", base.input.sourceHash, generated.exercises.single().id, "")
            assertNull(store.loadExerciseDraft("class", base.input.sourceHash, generated.exercises.single().id))
            val first = generated.exercises.single(); val graded = runtime.submitExercise(generated, ReviewConnection(), first.id, "A 因为")
            assertEquals(2, graded.exercises.size); assertEquals(first.id, graded.exercises.last().parentId)
            assertEquals("partial", graded.exercises.first().attempts.single().verdict)
            assertTrue(graded.qaThreads.single().id.startsWith("exercise:")); assertTrue(graded.qaThreads.single().turns.single().question.contains("A 因为"))
            assertEquals(1, graded.exerciseNotices.size); assertTrue(runtime.ackExerciseNotice(graded, graded.exerciseNotices.single().id))
            assertFalse(runtime.ackExerciseNotice(graded, graded.exerciseNotices.single().id))
            val reopened = ReviewStore(dir).load("class", base.input.sourceHash)!!; assertTrue(reopened.exerciseNotices.single().consumed)
            val restarted = ReviewRuntime(ReviewStore(dir), { true }, { gateway })
            assertFalse(restarted.ackExerciseNotice(reopened, graded.exerciseNotices.single().id))
            val again = runtime.submitExercise(reopened, ReviewConnection(), first.id, "A 因为"); assertEquals(2, again.exercises.size); assertEquals(1, gateway.grades)
            val child = again.exercises.last(); val childDone = runtime.submitExercise(again, ReviewConnection(), child.id, "另一答案")
            assertEquals(2, childDone.exercises.size); assertEquals(2, childDone.qaThreads.size)
        } finally { dir.deleteRecursively() }
    }
    @Test fun failurePersistsSameAttemptAndConcurrentSubmissionsDoNotDuplicate() = runBlocking {
        val dir = Files.createTempDirectory("exercise-retry").toFile()
        try {
            val base = run(); val store = ReviewStore(dir); store.save(base); val gateway = Gateway(); val runtime = ReviewRuntime(store, { true }, { gateway })
            val generated = runtime.generateExercises(base, ReviewConnection()); val id = generated.exercises.single().id; gateway.fails = true
            try { runtime.submitExercise(generated, ReviewConnection(), id, "答案"); fail("Expected failure") } catch (_: IllegalStateException) { }
            val failed = ReviewStore(dir).load("class", base.input.sourceHash)!!; val attempt = failed.exercises.single().attempts.single(); assertEquals("failed", attempt.status)
            gateway.fails = false
            val a = async { runtime.submitExercise(failed, ReviewConnection(), id, "答案", attempt.id) }
            val b = async { runtime.submitExercise(failed, ReviewConnection(), id, "答案", attempt.id) }
            a.await(); val result = b.await()
            assertEquals(attempt.id, result.exercises.first().attempts.single().id); assertEquals(2, result.exercises.size); assertEquals(1, result.qaThreads.size); assertEquals(2, gateway.grades)
        } finally { dir.deleteRecursively() }
    }
    @Test fun legacyRunWithoutExercisesDecodesAndBadRelationsFail() {
        val original = run(); val json = ReviewJson.run(original).apply { remove("exercises"); remove("exerciseNotices") }
        assertTrue(ReviewJson.run(json).exercises.isEmpty())
        val root = ReviewExercise("root", "unit", "SHORT", "问题", emptyList())
        val child = ReviewExercise("child", "unit", "SHORT", "追问", emptyList(), "root")
        assertThrows(IllegalArgumentException::class.java) { ReviewExerciseCodec.validate(original.copy(exercises = listOf(root, child, child.copy(id = "duplicate")))) }
        assertThrows(IllegalArgumentException::class.java) { ReviewExerciseCodec.validate(original.copy(exercises = List(6) { root.copy(id = "$it") })) }
        assertThrows(Exception::class.java) { ReviewJson.run(ReviewJson.run(original).put("exercises", "corrupt")) }
        assertThrows(Exception::class.java) { ReviewJson.run(ReviewJson.run(original).put("exerciseNotices", "corrupt")) }
        assertThrows(IllegalArgumentException::class.java) { ReviewExerciseCodec.validate(original.copy(exercises = listOf(root, root.copy(id = "dup", question = " 问题 ")))) }
        assertThrows(IllegalArgumentException::class.java) { ReviewExerciseCodec.validate(original.copy(exercises = listOf(root, child.copy(question = " 问题  ")))) }
        assertThrows(IllegalArgumentException::class.java) { ReviewExerciseCodec.validate(original.copy(exercises = listOf(root, child), exerciseNotices = listOf(ReviewExerciseNotice("n1", "child"), ReviewExerciseNotice("n2", "child")))) }
    }
    @Test fun cancellationPreservesAttemptForRetry() = runBlocking {
        val dir = Files.createTempDirectory("exercise-cancel").toFile()
        try {
            val base = run(); val store = ReviewStore(dir); store.save(base)
            val gateway = object : Gateway() {
                override suspend fun gradeExercise(input: ReviewInput, outline: ReviewOutline, units: List<ReviewUnit>, exercise: ReviewExercise, answer: String, allowFollowUp: Boolean): ReviewExerciseGrade { throw CancellationException("cancelled") }
            }
            val runtime = ReviewRuntime(store, { true }, { gateway }); val generated = runtime.generateExercises(base, ReviewConnection())
            try { runtime.submitExercise(generated, ReviewConnection(), generated.exercises.single().id, "回答"); fail("Expected cancellation") } catch (_: CancellationException) { }
            val attempt = ReviewStore(dir).load("class", base.input.sourceHash)!!.exercises.single().attempts.single()
            assertEquals("failed", attempt.status); assertEquals("回答", attempt.answer)
        } finally { dir.deleteRecursively() }
    }
    @Test fun sourceChangeBeforeAndAfterRequestPreservesOldQaAndNeverGrades() = runBlocking {
        val dir = Files.createTempDirectory("exercise-stale").toFile()
        try {
            val old = ReviewQaThread("old", "unit", listOf(ReviewQaTurn("旧问题", "旧答案")))
            val base = run().copy(qaThreads = listOf(old)); val store = ReviewStore(dir); store.save(base); var current = true
            val gateway = object : Gateway() {
                override suspend fun gradeExercise(input: ReviewInput, outline: ReviewOutline, units: List<ReviewUnit>, exercise: ReviewExercise, answer: String, allowFollowUp: Boolean): ReviewExerciseGrade {
                    current = false; return ReviewExerciseGrade("correct", "反馈", "思路", listOf("s"), null)
                }
            }
            val runtime = ReviewRuntime(store, { current }, { gateway }); val generated = runtime.generateExercises(base, ReviewConnection()); val id = generated.exercises.single().id
            current = false
            try { runtime.submitExercise(generated, ReviewConnection(), id, "答案"); fail("Expected stale") } catch (_: IllegalStateException) { }
            assertTrue(store.load("class", base.input.sourceHash)!!.exercises.single().attempts.isEmpty())
            current = true
            try { runtime.submitExercise(generated, ReviewConnection(), id, "答案"); fail("Expected stale") } catch (_: IllegalStateException) { }
            val saved = store.load("class", base.input.sourceHash)!!
            assertEquals(listOf(old), saved.qaThreads); assertEquals("failed", saved.exercises.single().attempts.single().status)
        } finally { dir.deleteRecursively() }
    }
    @Test fun rejectsFollowUpForCorrectChildOrPreviouslyExtendedRoot() = runBlocking {
        for (mode in listOf("correct", "child", "existing")) {
            val dir = Files.createTempDirectory("exercise-invalid").toFile()
            try {
                val root = ReviewExercise("root", "unit", "SHORT", "原题", emptyList())
                val child = ReviewExercise("child", "unit", "SHORT", "补题", emptyList(), "root")
                val base = run().copy(exercises = if (mode == "correct") listOf(root) else listOf(root, child)); val store = ReviewStore(dir); store.save(base)
                val gateway = object : Gateway() {
                    override suspend fun gradeExercise(input: ReviewInput, outline: ReviewOutline, units: List<ReviewUnit>, exercise: ReviewExercise, answer: String, allowFollowUp: Boolean) = ReviewExerciseGrade(if (mode == "correct") "correct" else "partial", "反馈", "思路", listOf("s"), ReviewExerciseDraft("unit", "SHORT", "新补题", emptyList()))
                }
                val runtime = ReviewRuntime(store, { true }, { gateway }); val id = if (mode == "child") "child" else "root"
                try { runtime.submitExercise(base, ReviewConnection(), id, "答案"); fail("Expected invalid follow-up") } catch (_: IllegalArgumentException) { }
                val saved = store.load("class", base.input.sourceHash)!!
                assertEquals(base.exercises.size, saved.exercises.size); assertTrue(saved.qaThreads.isEmpty()); assertEquals("failed", saved.exercises.single { it.id == id }.attempts.single().status)
            } finally { dir.deleteRecursively() }
        }
    }
}
