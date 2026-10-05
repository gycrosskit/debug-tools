package com.dgtang.debugtools.kuikly

import com.dgtang.debugtools.bugreport.*
import com.dgtang.debugtools.shake.ShakeStartResult
import com.tencent.kuikly.core.module.CallbackRef
import com.tencent.kuikly.core.module.Module
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume

/** 每页一个 Module；Store/摇动操作使用该页协程，dispose 在同一 Kuikly Context 调用。 */
class DebugToolsModule : Module(), BugReportStore, BugReportWorkspaceStore {
    private class Request(val id: String, val continuation: CancellableContinuation<JSONObject>, val keepAlive: Boolean) {
        var callback: CallbackRef? = null
    }
    private val json = Json { ignoreUnknownKeys = true }
    private val pending = mutableSetOf<Request>()
    private var nextId = 0L
    private var disposed = false
    private var configured = false
    private var shakeRequest: Request? = null
    private var shakeStarted = false
    private val shakeMutex = Mutex()
    private val mutableShakes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val shakes = mutableShakes.asSharedFlow()
    override fun moduleName() = NAME

    suspend fun configure(
        namespace: String, keyAlias: String,
        tokenKey: String = "zentao_token", shakeEnabledKey: String = "shake_enabled",
        historyKey: String = "submission_history", draftKey: String = "bug_draft", pendingKey: String = "pending_bugs",
    ) {
        val values = listOf(namespace, keyAlias, tokenKey, shakeEnabledKey, historyKey, draftKey, pendingKey)
        require(values.all { it.matches(Regex("[A-Za-z0-9_.-]{1,128}")) })
        require(values.drop(2).distinct().size == 5)
        await("configure", JSONObject().apply {
            put("namespace", namespace); put("keyAlias", keyAlias); put("tokenKey", tokenKey)
            put("shakeEnabledKey", shakeEnabledKey); put("historyKey", historyKey)
            put("draftKey", draftKey); put("pendingKey", pendingKey)
        })
        configured = true
    }

    override suspend fun readToken() = read("readToken")
    override suspend fun writeToken(value: String) = write("writeToken", value)
    override suspend fun clearCredentials() { storeReady(); await("clearCredentials") }
    override suspend fun readShakeEnabled() = read("readShakeEnabled").toBooleanStrict()
    override suspend fun writeShakeEnabled(value: Boolean) = write("writeShakeEnabled", value.toString())
    override suspend fun readHistory(): List<BugSubmissionRecord> = json.decodeFromString(read("readHistory"))
    override suspend fun writeHistory(value: List<BugSubmissionRecord>) = write("writeHistory", json.encodeToString(value))
    override suspend fun readDraft(): BugReportDraft = json.decodeFromString(read("readDraft"))
    override suspend fun writeDraft(value: BugReportDraft) = write("writeDraft", json.encodeToString(value))
    override suspend fun readPending(): List<PendingBugReport> = json.decodeFromString(read("readPending"))
    override suspend fun writePending(value: List<PendingBugReport>) = write("writePending", json.encodeToString(value))

    /** 原生注册后才返回 STARTED；公开 suspend 合同避免把入队当作同步成功。 */
    suspend fun startShake(): ShakeStartResult = shakeMutex.withLock {
        if (disposed) return@withLock ShakeStartResult.CLOSED
        if (shakeStarted) return@withLock ShakeStartResult.STARTED
        val response = await("startShake", keepAlive = true)
        val result = ShakeStartResult.entries.firstOrNull { it.name == response.optString("value") }
            ?: error("Invalid shake registration response")
        shakeStarted = result == ShakeStartResult.STARTED
        if (!shakeStarted) clearShake()
        result
    }
    suspend fun stopShake() = shakeMutex.withLock {
        if (disposed) return@withLock
        clearShake()
        await("stopShake")
    }

    private suspend fun read(method: String): String { storeReady(); return await(method).optString("value") }
    private suspend fun write(method: String, value: String) {
        storeReady(); require(value.length <= 1_048_576)
        await(method, JSONObject().apply { put("value", value) })
    }
    private fun storeReady() { check(configured && !disposed) { "Configure an active debug store first" } }

    private suspend fun await(method: String, args: JSONObject = JSONObject(), keepAlive: Boolean = false): JSONObject {
        check(!disposed) { "DebugToolsModule is disposed" }
        val dispatcher = coroutineContext[ContinuationInterceptor] as CoroutineDispatcher
        val result = withTimeout(20_000) {
            suspendCancellableCoroutine<JSONObject> { continuation ->
                val request = Request((++nextId).toString(), continuation, keepAlive)
                args.put("requestId", request.id)
                pending.add(request)
                if (keepAlive) shakeRequest = request
                continuation.invokeOnCancellation {
                    // 取消可能来自后台；不依赖已经取消的 Job 访问 Kuikly callback 表。
                    dispatcher.dispatch(EmptyCoroutineContext) {
                        pending.remove(request)
                        request.callback?.let(::removeCallback); request.callback = null
                        if (shakeRequest === request) { shakeRequest = null; shakeStarted = false }
                        if (!disposed) asyncToNativeMethod("cancel", JSONObject().apply { put("requestId", request.id) }, null)
                    }
                }
                request.callback = toNative(keepAlive, method, args.toString(), { response ->
                    if (disposed || response == null) return@toNative
                    if (response.optString("status") == "shake") {
                        if (shakeStarted && shakeRequest === request) mutableShakes.tryEmit(Unit)
                    } else {
                        pending.remove(request)
                        if (continuation.isActive) continuation.resume(response)
                        if (!keepAlive) { request.callback?.let(::removeCallback); request.callback = null }
                    }
                }, false).callbackRef
                if (!continuation.isActive && !keepAlive) {
                    request.callback?.let(::removeCallback); request.callback = null
                }
            }
        }
        if (disposed) throw CancellationException("DebugToolsModule is disposed")
        if (result.optString("status") != "ok") {
            if (keepAlive) clearShake()
            error("Native debug operation failed")
        }
        return result
    }
    private fun clearShake() {
        shakeStarted = false
        shakeRequest?.callback?.let(::removeCallback)
        shakeRequest?.callback = null
        shakeRequest = null
    }
    fun dispose() {
        if (disposed) return
        clearShake()
        // 原生 onDestroy 也会 close；提前取消当前传感器，撤销仍等待的 Store 回执。
        asyncToNativeMethod("stopShake", JSONObject().apply { put("requestId", (++nextId).toString()) }, null)
        pending.forEach { request ->
            asyncToNativeMethod("cancel", JSONObject().apply { put("requestId", request.id) }, null)
        }
        disposed = true
        pending.toList().forEach { it.continuation.cancel() }
        pending.clear()
    }
    companion object { const val NAME = "GycDebugToolsModule" }
}
