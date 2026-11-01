package com.dgtang.debugtools.kuikly

import com.dgtang.debugtools.shake.ShakeStartResult
import com.tencent.kuikly.core.global.GlobalFunctions
import com.tencent.kuikly.core.manager.BridgeManager
import com.tencent.kuikly.core.manager.NativeMethod
import com.tencent.kuikly.core.manager.KotlinMethod
import com.tencent.kuikly.core.nvi.NativeBridge
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

// 真实 Module/GlobalFunctions/NativeBridge，只以 delegate 控制原生时序和异常。
private class Renderer(val page: String) : AutoCloseable {
    data class Call(val method: String, val params: String, val callback: String?)
    val module = DebugToolsModule().also { it.pagerId = page }
    val calls = mutableListOf<Call>()
    var onCall: (Call) -> Unit = { call -> reply(call, if (call.method == "startShake") "STARTED" else "") }
    init {
        BridgeManager.init(false)
        NativeBridge.isContextThread = true
        BridgeManager.registerNativeBridge(page, NativeBridge().also { bridge ->
            bridge.delegate = object : NativeBridge.NativeBridgeDelegate {
                override fun callNative(methodId: Int, arg0: Any?, arg1: Any?, arg2: Any?, arg3: Any?, arg4: Any?, arg5: Any?): Any? {
                    if (methodId == NativeMethod.CALL_MODULE_METHOD) {
                        val call = Call(arg2 as String, arg3 as String, arg4 as? String)
                        calls += call
                        onCall(call)
                    }
                    return null
                }
            }
        })
    }
    fun reply(call: Call, value: String = "", status: String = "ok") {
        call.callback?.let { GlobalFunctions.invokeFunction(page, it, JSONObject().apply {
            put("status", status); put("value", value)
        }.toString()) }
    }
    fun callbackCount(): Int {
        val field = GlobalFunctions::class.java.getDeclaredField("functionMap").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        return ((field.get(null) as Map<String, Map<String, *>>)[page]?.size ?: 0)
    }
    override fun close() {
        try { module.dispose() } finally {
            GlobalFunctions.destroyGlobalFunction(page)
            BridgeManager.callKotlinMethod(KotlinMethod.DESTROY_INSTANCE, page, null, null, null, null, null)
            NativeBridge.isContextThread = false
        }
    }
}

class DebugToolsModuleTest {
    @org.junit.Test fun senderContracts() = runBlocking {
        val failures = mutableListOf<String>()
        var passed = 0
        suspend fun scenario(name: String, block: suspend CoroutineScope.() -> Unit) {
            try { supervisorScope { block() }; passed++; println("PASS: $name") }
            catch (error: Throwable) { failures += "$name: $error"; println("FAIL: ${failures.last()}") }
        }
        scenario("synchronous configure/read callbacks are released once") {
            Renderer("debug-sync").use { r ->
                r.module.configure("brand-debug", "brand-key")
                r.onCall = { r.reply(it, "token") }
                check(r.module.readToken() == "token")
                check(r.callbackCount() == 0)
            }
        }
        scenario("native failure after callback registration releases callback") {
            Renderer("debug-throw").use { r ->
                r.module.configure("brand-debug", "brand-key")
                r.onCall = { if (it.method == "readToken") error("native unavailable") }
                check(runCatching { r.module.readToken() }.isFailure)
                yield()
                check(r.callbackCount() == 0) { "callback leaked" }
            }
        }
        scenario("keepAlive failure cleans shake callback and permits a later start") {
            Renderer("debug-shake-throw").use { r ->
                r.onCall = { if (it.method == "startShake") error("native unavailable") }
                check(runCatching { r.module.startShake() }.isFailure)
                yield()
                check(r.callbackCount() == 0) { "shake callback leaked" }
                r.onCall = { r.reply(it, "STARTED") }
                check(r.module.startShake() == ShakeStartResult.STARTED)
                r.module.stopShake()
                check(r.callbackCount() == 0)
            }
        }
        scenario("keepAlive synchronous ready, stop, late event and restart") {
            Renderer("debug-shake").use { r ->
                var events = 0
                val collector = launch(start = CoroutineStart.UNDISPATCHED) { r.module.shakes.collect { events++ } }
                try {
                    check(r.module.startShake() == ShakeStartResult.STARTED)
                    val first = r.calls.first()
                    check(r.callbackCount() == 1)
                    r.reply(first, status = "shake"); yield(); check(events == 1)
                    r.module.stopShake()
                    r.reply(first, status = "shake"); yield(); check(events == 1)
                    check(r.callbackCount() == 0)
                    check(r.module.startShake() == ShakeStartResult.STARTED)
                } finally { collector.cancelAndJoin() }
            }
        }
        scenario("synchronous keepAlive receipt remains valid if native throws after acknowledging") {
            Renderer("debug-shake-accepted").use { r ->
                var events = 0
                val collector = launch(start = CoroutineStart.UNDISPATCHED) { r.module.shakes.collect { events++ } }
                try {
                    r.onCall = { r.reply(it, "STARTED"); if (it.method == "startShake") error("after receipt") }
                    check(r.module.startShake() == ShakeStartResult.STARTED)
                    check(r.callbackCount() == 1)
                    r.reply(r.calls.first(), status = "shake"); yield(); check(events == 1)
                    r.module.stopShake()
                    check(r.callbackCount() == 0)
                } finally { collector.cancelAndJoin() }
            }
        }
        scenario("dispose rejects synchronous late results from native controls") {
            Renderer("debug-dispose-late").use { r ->
                r.module.configure("brand-debug", "brand-key")
                r.onCall = { }
                val pending = async { r.module.readToken() }
                yield()
                val old = r.calls.last()
                r.onCall = { if (it.method == "stopShake") r.reply(old, "late") }
                r.module.dispose(); yield()
                check(pending.isCancelled)
                check(r.callbackCount() == 0)
            }
        }
        scenario("cancel removes callback despite native cancel throwing") {
            Renderer("debug-cancel").use { r ->
                r.module.configure("brand-debug", "brand-key")
                r.onCall = { if (it.method == "cancel") error("cancel unavailable") }
                val pending = async { r.module.readToken() }
                yield()
                val call = r.calls.last()
                pending.cancelAndJoin(); yield()
                check(r.callbackCount() == 0)
                r.reply(call, "late"); yield()
                check(pending.isCancelled)
            }
        }
        scenario("dispose settles all pending when each native control throws; repeat is closed") {
            Renderer("debug-dispose").use { r ->
                r.module.configure("brand-debug", "brand-key")
                r.onCall = { }
                val first = async { r.module.readToken() }
                val second = async { r.module.readToken() }
                yield()
                val old = r.calls.filter { it.method == "readToken" }
                r.onCall = { if (it.method == "stopShake" || it.method == "cancel") error("control unavailable") }
                check(runCatching { r.module.dispose() }.isSuccess) { "native failure interrupted dispose" }
                yield()
                check(first.isCancelled && second.isCancelled)
                check(r.callbackCount() == 0)
                check(r.calls.count { it.method == "cancel" } == 2)
                val count = r.calls.size
                r.module.dispose()
                check(r.calls.size == count)
                check(r.module.startShake() == ShakeStartResult.CLOSED)
                check(runCatching { r.module.readToken() }.isFailure)
                old.forEach { r.reply(it, "late") }
                check(r.callbackCount() == 0)
            }
        }
        check(failures.isEmpty()) { "${failures.size} failed: ${failures.joinToString()}" }
        println("DebugTools production sender: $passed passed; 0 skipped")
    }
}
