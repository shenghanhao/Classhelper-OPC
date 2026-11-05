package com.classmemory.app.review

import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Atomic per-content snapshots. Corruption is reported; existing data is never reset. */
class ReviewStore(private val directory: File) {
    private val lock = Mutex()
    private val mutableChanges = MutableStateFlow(0L)
    val changes: StateFlow<Long> = mutableChanges

    private fun sessionDirectory(sessionId: String): File {
        require(sessionId.isNotBlank())
        val encoded = MessageDigest.getInstance("SHA-256").digest(sessionId.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
        return contained(File(directory, encoded))
    }
    private fun contained(file: File): File {
        require(file.canonicalPath.startsWith(directory.canonicalPath + File.separator)) { "Unsafe review storage path" }
        return file
    }
    private fun path(sessionId: String, hash: String): File {
        require(Regex("[a-f0-9]{64}").matches(hash)) { "Invalid review source hash" }
        return contained(File(sessionDirectory(sessionId), "$hash.json"))
    }
    private fun read(file: File): ReviewRun = ReviewJson.run(JSONObject(contained(file).readText())).also {
        check(path(it.input.sessionId, it.input.sourceHash).canonicalFile == file.canonicalFile) { "Review file identity mismatch" }
    }
    private fun readLatest(folder: File): ReviewRun? {
        contained(folder)
        if (!folder.exists()) return null
        check(folder.isDirectory) { "Review storage is not a directory" }
        val files = folder.listFiles() ?: error("Cannot read review storage")
        return files.filter { it.extension == "json" }.map { read(it) }.maxWithOrNull(compareBy<ReviewRun> { it.updatedAt }.thenBy { it.input.sourceHash })
    }
    suspend fun load(sessionId: String, sourceHash: String): ReviewRun? = lock.withLock {
        withContext(Dispatchers.IO) { path(sessionId, sourceHash).let { if (it.exists()) read(it) else null } }
    }
    suspend fun latest(sessionId: String): ReviewRun? = lock.withLock {
        withContext(Dispatchers.IO) { readLatest(sessionDirectory(sessionId)) }
    }
    suspend fun listVersions(sessionId: String): List<ReviewRun> = lock.withLock {
        withContext(Dispatchers.IO) {
            val folder = sessionDirectory(sessionId)
            if (!folder.exists()) emptyList() else {
                check(folder.isDirectory) { "Review storage is not a directory" }
                (folder.listFiles() ?: error("Cannot read review storage")).filter { it.extension == "json" }.map { read(it) }.sortedWith(compareByDescending<ReviewRun> { it.updatedAt }.thenBy { it.input.sourceHash })
            }
        }
    }
    suspend fun allLatest(): List<ReviewRun> = lock.withLock {
        withContext(Dispatchers.IO) {
            if (!directory.exists()) emptyList()
            else {
                check(directory.isDirectory) { "Review storage is not a directory" }
                (directory.listFiles() ?: error("Cannot read review storage"))
                    .filter { it.isDirectory }.mapNotNull { readLatest(it) }.sortedByDescending { it.updatedAt }
            }
        }
    }
    suspend fun save(run: ReviewRun) = lock.withLock {
        withContext(Dispatchers.IO) { write(run) }
    }
    suspend fun deleteSession(sessionId: String) = lock.withLock {
        withContext(Dispatchers.IO) {
            val folder = sessionDirectory(sessionId)
            if (folder.exists()) {
                check(folder.deleteRecursively()) { "Class deleted, but its review material could not be removed / 课堂已删除，但复习资料清理失败" }
                mutableChanges.value += 1
            }
        }
    }
    suspend fun markReviewed(sessionId: String, sourceHash: String) = lock.withLock {
        withContext(Dispatchers.IO) {
            val previous = read(path(sessionId, sourceHash))
            check(previous.plan != null) { "Review plan is incomplete" }
            check(readLatest(sessionDirectory(sessionId))?.input?.sourceHash == sourceHash) { "A newer review exists" }
            if (previous.reviewedAt == null) write(previous.copy(reviewedAt = System.currentTimeMillis(), completedStepIndices = previous.plan.steps.indices.toSet()), preserveProgress = false)
        }
    }
    suspend fun setStepCompleted(sessionId: String, sourceHash: String, planFingerprint: String, stepIndex: Int, completed: Boolean) = lock.withLock {
        withContext(Dispatchers.IO) {
            val previous = read(path(sessionId, sourceHash))
            val plan = checkNotNull(previous.plan) { "Review plan is incomplete" }
            check(readLatest(sessionDirectory(sessionId))?.input?.sourceHash == sourceHash) { "A newer review exists" }
            check(plan.fingerprint() == planFingerprint && stepIndex in plan.steps.indices) { "Review plan changed; refresh" }
            val indices = if (completed) previous.completedStepIndices + stepIndex else previous.completedStepIndices - stepIndex
            write(previous.copy(completedStepIndices = indices, reviewedAt = if (indices.size == plan.steps.size) previous.reviewedAt ?: System.currentTimeMillis() else null), preserveProgress = false)
        }
    }
    private fun write(run: ReviewRun, preserveProgress: Boolean = true) {
        ReviewContracts.validate(run)
        val file = path(run.input.sessionId, run.input.sourceHash)
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs()) { "Cannot create review directory" }
        // Preserve reviewed state even if an older in-flight stage saves again.
        val existing = if (file.exists()) read(file) else null
        val next = if (preserveProgress && existing?.plan != null && run.plan?.fingerprint() == existing.plan.fingerprint()) run.copy(reviewedAt = existing.reviewedAt, completedStepIndices = existing.completedStepIndices) else run
        ReviewContracts.validate(next)
        val temporary = File.createTempFile("review-", ".tmp", file.parentFile)
        try {
            FileOutputStream(temporary).use { output -> output.write(ReviewJson.run(next).toString().toByteArray(Charsets.UTF_8)); output.fd.sync() }
            check(temporary.renameTo(file)) { "Cannot save review" }
            mutableChanges.value += 1
        } finally { temporary.delete() }
    }
}
