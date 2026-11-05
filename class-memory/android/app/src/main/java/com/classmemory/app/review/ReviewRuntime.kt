package com.classmemory.app.review

import com.classmemory.app.repository.SessionRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/** Process-owned jobs survive navigation. Durable checkpoints allow explicit retry after process death. */
class ReviewRuntime private constructor(val store: ReviewStore, private val repo: SessionRepository?, private val matches: suspend (ReviewRun) -> Boolean, private val gatewayFactory: (ReviewConnection) -> ReviewGateway) {
    constructor(store: ReviewStore, repo: SessionRepository) : this(store, repo, { repo.reviewMatchesCurrent(it) }, { when (it.mode) { ReviewConnectionMode.DIRECT_DEEPSEEK -> DirectDeepSeekGateway(it.apiKey, it.model); ReviewConnectionMode.PROXY -> ReviewHttpGateway(it.baseUrl, it.accessToken) } })
    internal constructor(store: ReviewStore, matches: suspend (ReviewRun) -> Boolean, gatewayFactory: (ReviewConnection) -> ReviewGateway) : this(store, null, matches, gatewayFactory)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val jobs = mutableMapOf<String, Job>()
    private val mutableProgress = MutableStateFlow<Map<String, String>>(emptyMap())
    private val mutableErrors = MutableStateFlow<Map<String, String>>(emptyMap())
    val progress: StateFlow<Map<String, String>> = mutableProgress
    val errors: StateFlow<Map<String, String>> = mutableErrors

    private val qaLock = Mutex()

    fun start(input: ReviewInput, connection: ReviewConnection) {
        if (jobs[input.sessionId]?.isActive == true) return
        mutableErrors.update { it - input.sessionId }
        mutableProgress.update { it + (input.sessionId to "outline") }
        jobs[input.sessionId] = scope.launch {
            try {
                val gateway = gatewayFactory(connection)
                withContext(Dispatchers.Default) {
                    qaLock.withLock {
                        ReviewCoordinator(store, gateway) { id -> requireNotNull(repo).reviewSourceHash(id, input.language) }.run(input) { phase ->
                            mutableProgress.update { it + (input.sessionId to phase) }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableErrors.update { it + (input.sessionId to (e.message ?: "Review failed / 复习方案生成失败")) }
            } finally {
                mutableProgress.update { it - input.sessionId }
            }
        }
    }

    suspend fun ask(run: ReviewRun, connection: ReviewConnection, question: String, targetUnitId: String? = null, threadId: String? = null, retryTurnId: String? = null, newThreadId: String? = null, newTurnId: String? = null): ReviewRun = qaLock.withLock {
        require(question.isNotBlank())
        val current = requireNotNull(store.load(run.input.sessionId, run.input.sourceHash))
        check(matches(current)) { "内容已变更，请先更新复习方案" }
        val outline = requireNotNull(current.outline)
        require(targetUnitId == null || targetUnitId in current.units)
        val previous = threadId?.let { id -> current.qaThreads.single { it.id == id } }
        require(previous == null || previous.targetUnitId == targetUnitId)
        val retry = retryTurnId?.let { id -> requireNotNull(previous).turns.single { it.id == id && it.status != "answered" && it.question == question } }
        val pending = ReviewQaTurn(question, "", id = retry?.id ?: newTurnId ?: UUID.randomUUID().toString(), status = "pending")
        val baseThread = previous ?: ReviewQaThread(newThreadId ?: UUID.randomUUID().toString(), targetUnitId, emptyList())
        require(previous != null || current.qaThreads.none { it.id == baseThread.id })
        require(retry != null || baseThread.turns.none { it.id == pending.id })
        val retryIndex = retry?.let { baseThread.turns.indexOfFirst { turn -> turn.id == it.id } } ?: baseThread.turns.size
        val history = baseThread.turns.take(retryIndex).filter { it.status == "answered" }
        val pendingTurns = if (retry != null) baseThread.turns.map { if (it.id == retry.id) pending else it } else baseThread.turns + pending
        val pendingThread = baseThread.copy(turns = pendingTurns)
        store.save(current.copy(qaThreads = current.qaThreads.filterNot { it.id == baseThread.id } + pendingThread, updatedAt = System.currentTimeMillis()))
        val result = try {
            gatewayFactory(connection).qa(current.input, outline, outline.topics.map { current.units.getValue(it.id) }, targetUnitId, question, history, current.input.notes.map { it.id }).also { result ->
                require(result.answerMarkdown.isNotBlank() && result.sourceIds.all { id -> current.input.sources.any { it.id == id } })
                require(result.updates.keys.all { it in current.units && targetUnitId != null && it == targetUnitId })
                require(!result.clarificationNeeded || result.updates.isEmpty())
                check(matches(current)) { "内容已变更，请先更新复习方案" }
            }
        } catch (e: Exception) {
            withContext(NonCancellable) {
                val saved = store.load(current.input.sessionId, current.input.sourceHash)
                if (saved != null) store.save(saved.copy(qaThreads = saved.qaThreads.map { t -> if (t.id == baseThread.id) t.copy(turns = t.turns.map { if (it.id == pending.id) it.copy(status = "failed") else it }) else t }))
            }
            throw e
        }
        val thread = pendingThread.copy(turns = pendingThread.turns.map { if (it.id == pending.id) ReviewQaTurn(question, result.answerMarkdown, result.clarificationNeeded, result.sourceIds, pending.id) else it })
        val next = current.copy(qaThreads = current.qaThreads.filterNot { it.id == thread.id } + thread, units = current.units.mapValues { (id, unit) -> result.updates[id]?.let { unit.copy(markdown = it) } ?: unit }, unitVersions = current.unitVersions + result.updates.mapValues { (id, _) -> current.unitVersions[id].orEmpty() + current.units.getValue(id).markdown }, updatedAt = System.currentTimeMillis())
        store.save(next); requireNotNull(store.load(current.input.sessionId, current.input.sourceHash))
    }
    suspend fun generateGraph(run: ReviewRun, connection: ReviewConnection): ReviewRun = qaLock.withLock {
        val current = requireNotNull(store.load(run.input.sessionId, run.input.sourceHash)); val outline = requireNotNull(current.outline)
        check(matches(current)) { "内容已变更，请先更新复习方案" }
        val graph = gatewayFactory(connection).graph(current.input, outline, outline.topics.map { current.units.getValue(it.id) })
        require(graph.nodes.distinct().size == graph.nodes.size && graph.nodes.toSet() == current.units.keys)
        require(graph.edges.all { it.fromUnitId in current.units && it.toUnitId in current.units && it.relation in setOf("derives", "depends_on", "parallel", "related") })
        check(matches(current)) { "内容已变更，请先更新复习方案" }
        store.save(current.copy(graph = graph, updatedAt = System.currentTimeMillis())); requireNotNull(store.load(current.input.sessionId, current.input.sourceHash))
    }

    fun cancel(sessionId: String) { jobs[sessionId]?.cancel() }

    suspend fun isCurrent(run: ReviewRun, prepared: ReviewInput? = null): Boolean = repo?.reviewMatchesCurrent(run, prepared) ?: matches(run)

    suspend fun setStepCompleted(run: ReviewRun, index: Int, completed: Boolean) {
        check(matches(run)) { "内容已变更，请先更新复习方案" }
        store.setStepCompleted(run.input.sessionId, run.input.sourceHash, requireNotNull(run.plan).fingerprint(), index, completed)
    }

    suspend fun markReviewed(run: ReviewRun) {
        check(matches(run)) {
            "Source changed; refresh the review first / 内容已变更，请先更新复习方案"
        }
        store.markReviewed(run.input.sessionId, run.input.sourceHash)
    }
}
