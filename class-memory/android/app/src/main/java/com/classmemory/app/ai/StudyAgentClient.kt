package com.classmemory.app.ai

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resumeWithException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import org.json.JSONObject

enum class StudyClientError {
    NOT_CONFIGURED,
    INSECURE_URL,
    NETWORK,
    TIMEOUT,
    UNAUTHORIZED,
    RATE_LIMITED,
    SERVER,
    BAD_RESPONSE,
}

class StudyAgentException(
    val kind: StudyClientError,
    val httpStatus: Int? = null,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * HTTPS-only client for the study-agent proxy. The proxy owns the model provider key; this
 * client never sees it. One call has a hard 60 second budget and is never retried automatically.
 */
class StudyAgentClient(
    baseUrl: String,
    private val client: OkHttpClient = defaultClient(),
) {
    private val endpoint: String = endpointFor(baseUrl)
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun analyze(input: StudyInput, snapshot: StudySnapshot): StudyAnalysis {
        val request = Request.Builder()
            .url(endpoint)
            .header("Accept", "application/json")
            .post(input.toRequestJson().toRequestBody(jsonMediaType))
            .build()
        val response = try {
            await(request)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: SocketTimeoutException) {
            throw StudyAgentException(StudyClientError.TIMEOUT, message = "Study request timed out", cause = failure)
        } catch (failure: InterruptedIOException) {
            throw StudyAgentException(StudyClientError.TIMEOUT, message = "Study request timed out", cause = failure)
        } catch (failure: IOException) {
            throw StudyAgentException(StudyClientError.NETWORK, message = "Could not reach the study service", cause = failure)
        }
        response.use { result ->
            val responseBody = result.body
                ?: throw StudyAgentException(StudyClientError.BAD_RESPONSE, result.code, "Study service returned no body")
            val body = withContext(Dispatchers.IO) { readLimited(responseBody) }
            if (!result.isSuccessful) {
                throw errorFor(result.code, body)
            }
            val analysis = try {
                StudyContracts.parseAnalysis(body, snapshot)
            } catch (failure: StudyContractException) {
                throw StudyAgentException(
                    StudyClientError.BAD_RESPONSE,
                    result.code,
                    "Study service returned an invalid analysis: ${failure.message}",
                    failure,
                )
            }
            if (analysis.sourceHash != input.sourceHash) {
                throw StudyAgentException(StudyClientError.BAD_RESPONSE, result.code, "Study result belongs to different content")
            }
            return analysis
        }
    }

    private suspend fun await(request: Request): Response = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, failure: IOException) {
                if (!continuation.isCancelled) continuation.resumeWithException(failure)
            }

            override fun onResponse(call: Call, response: Response) {
                if (continuation.isCancelled) {
                    response.close()
                } else {
                    continuation.resume(response, null)
                }
            }
        })
    }

    private fun errorFor(status: Int, body: String): StudyAgentException {
        val kind = when (status) {
            401, 403 -> StudyClientError.UNAUTHORIZED
            429 -> StudyClientError.RATE_LIMITED
            in 500..599 -> StudyClientError.SERVER
            else -> StudyClientError.BAD_RESPONSE
        }
        val detail = runCatching {
            JSONObject(body).optJSONObject("error")?.optString("message").orEmpty()
        }.getOrNull().orEmpty().trim().take(200)
        val message = if (detail.isEmpty()) "Study service returned HTTP $status" else detail
        return StudyAgentException(kind, status, message)
    }

    private fun readLimited(body: ResponseBody): String {
        val stream = body.byteStream()
        val output = ByteArrayOutputStream()
        val chunk = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val read = stream.read(chunk)
            if (read < 0) break
            total += read
            if (total > MAX_RESPONSE_BYTES) {
                throw StudyAgentException(StudyClientError.BAD_RESPONSE, message = "Study response is too large")
            }
            output.write(chunk, 0, read)
        }
        return String(output.toByteArray(), Charsets.UTF_8)
    }

    companion object {
        const val MAX_RESPONSE_BYTES = 256 * 1024
        private const val PATH = "/v1/study/analyze"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()

        fun endpointFor(baseUrl: String): String {
            val trimmed = baseUrl.trim()
            if (trimmed.isEmpty()) {
                throw StudyAgentException(StudyClientError.NOT_CONFIGURED, message = "Study service is not configured")
            }
            val uri = try {
                URI(trimmed)
            } catch (failure: Exception) {
                throw StudyAgentException(StudyClientError.INSECURE_URL, message = "Study service URL is invalid", cause = failure)
            }
            if (uri.scheme != "https" || uri.host.isNullOrBlank()) {
                throw StudyAgentException(StudyClientError.INSECURE_URL, message = "Study service must use HTTPS")
            }
            return trimmed.trimEnd('/') + PATH
        }
    }
}
