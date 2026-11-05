package com.classmemory.app.ai

import com.classmemory.app.repository.SessionRepository
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class StudyPrepareError {
    SESSION_NOT_FOUND,
    NO_FINAL_TRANSCRIPT,
    INPUT_TOO_LARGE,
}

sealed interface StudyPreparation {
    data class Ready(
        val snapshot: StudySnapshot,
        val input: StudyInput,
        val cached: StudyRecord?,
    ) : StudyPreparation

    data class Rejected(
        val reason: StudyPrepareError,
        val message: String,
    ) : StudyPreparation
}

enum class StudyRunError {
    CONFIGURATION,
    NETWORK,
    TIMEOUT,
    UNAUTHORIZED,
    RATE_LIMITED,
    SERVER,
    BAD_RESPONSE,
    STALE,
    DELETED,
    STORE_FAILED,
}

sealed interface StudyRunResult {
    data class Success(val record: StudyRecord, val fromCache: Boolean) : StudyRunResult
    data class Failure(val error: StudyRunError, val message: String) : StudyRunResult
}

/**
 * One-semester analysis orchestration. The UI never builds prompts or talks to OkHttp:
 * it prepares a snapshot, shows the exact text stats for consent, then calls [analyze].
 */
class StudyAgent(
    private val snapshotSource: suspend (String) -> StudySnapshot?,
    private val currentSourceHash: suspend (String) -> String?,
    private val client: StudyAgentClient,
    private val store: StudyResultStore,
    private val now: () -> Long = System::currentTimeMillis,
    private val requestIdFactory: () -> String = { UUID.randomUUID().toString() },
) {
    constructor(
        repository: SessionRepository,
        client: StudyAgentClient,
        store: StudyResultStore,
    ) : this(
        snapshotSource = repository::studySnapshot,
        currentSourceHash = { sessionId -> repository.studySnapshot(sessionId)?.sourceHash },
        client = client,
        store = store,
    )

    private val runLock = Mutex()

    suspend fun prepare(sessionId: String, language: String): StudyPreparation {
        val snapshot = snapshotSource(sessionId)
            ?: return StudyPreparation.Rejected(StudyPrepareError.SESSION_NOT_FOUND, "Class not found")
        val input = try {
            StudyInput.from(snapshot, language, requestIdFactory())
        } catch (failure: StudyContractException) {
            val reason = when (failure.code) {
                StudyErrorCode.EMPTY_TRANSCRIPT -> StudyPrepareError.NO_FINAL_TRANSCRIPT
                StudyErrorCode.INPUT_TOO_LARGE -> StudyPrepareError.INPUT_TOO_LARGE
                else -> StudyPrepareError.INPUT_TOO_LARGE
            }
            return StudyPreparation.Rejected(reason, failure.message.orEmpty())
        }
        val latest = store.latestForSession(sessionId)
        val cached = latest?.takeIf { it.sourceHash == snapshot.sourceHash }
        return StudyPreparation.Ready(snapshot, input, cached)
    }

    /**
     * [force] is only for an explicit "analyze again" action. A cached result with the same
     * source hash is returned without any network call, and concurrent callers are serialized.
     */
    suspend fun analyze(preparation: StudyPreparation.Ready, force: Boolean = false): StudyRunResult {
        return runLock.withLock {
            val sessionId = preparation.snapshot.sessionId
            val beforeHash = currentSourceHash(sessionId)
                ?: return@withLock StudyRunResult.Failure(StudyRunError.DELETED, "Class was deleted before analysis")
            if (beforeHash != preparation.snapshot.sourceHash) {
                return@withLock StudyRunResult.Failure(StudyRunError.STALE, "Class content changed before analysis")
            }
            if (!force) {
                val latest = store.latestForSession(sessionId)
                if (latest != null && latest.sourceHash == beforeHash) {
                    return@withLock StudyRunResult.Success(latest, fromCache = true)
                }
            }
            val analysis = try {
                client.analyze(preparation.input, preparation.snapshot)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: StudyAgentException) {
                return@withLock StudyRunResult.Failure(failure.kind.toRunError(), failure.message.orEmpty())
            } catch (failure: IOException) {
                return@withLock StudyRunResult.Failure(StudyRunError.NETWORK, failure.message.orEmpty())
            }
            val currentHash = currentSourceHash(sessionId)
                ?: return@withLock StudyRunResult.Failure(StudyRunError.DELETED, "Class was deleted during analysis")
            if (currentHash != preparation.snapshot.sourceHash) {
                return@withLock StudyRunResult.Failure(StudyRunError.STALE, "Class content changed during analysis")
            }
            val record = StudyRecord.create(
                analysis = analysis,
                snapshot = preparation.snapshot,
                input = preparation.input,
                analyzedAt = now(),
                model = null,
            )
            return@withLock try {
                store.save(record)
                StudyRunResult.Success(record, fromCache = false)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                StudyRunResult.Failure(StudyRunError.STORE_FAILED, failure.message.orEmpty())
            }
        }
    }

    private fun StudyClientError.toRunError(): StudyRunError = when (this) {
        StudyClientError.NOT_CONFIGURED, StudyClientError.INSECURE_URL -> StudyRunError.CONFIGURATION
        StudyClientError.NETWORK -> StudyRunError.NETWORK
        StudyClientError.TIMEOUT -> StudyRunError.TIMEOUT
        StudyClientError.UNAUTHORIZED -> StudyRunError.UNAUTHORIZED
        StudyClientError.RATE_LIMITED -> StudyRunError.RATE_LIMITED
        StudyClientError.SERVER -> StudyRunError.SERVER
        StudyClientError.BAD_RESPONSE -> StudyRunError.BAD_RESPONSE
    }
}
