@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package com.dgtang.debugtools.verification

import com.dgtang.debugtools.bugreport.IosBugReportStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import platform.Foundation.NSUserDefaults

/** 只用于独立 Simulator App；不读取宿主命名空间或真实凭据。 */
fun keychainReplacementCheck(): String = runBlocking {
    val namespace = "gycrosskit-review-keychain-20261008"
    val preferences = NSUserDefaults(suiteName = namespace)
    val store = IosBugReportStore(Json, namespace, "fixture", namespace, preferences)
    try {
        store.clearCredentials()
        check(store.readToken() == "")
        store.writeToken("fixture-one")
        val reopened = IosBugReportStore(Json, namespace, "fixture", namespace, preferences)
        check(reopened.readToken() == "fixture-one")
        reopened.writeToken("fixture-two")
        check(store.readToken() == "fixture-two")
        reopened.clearCredentials()
        check(store.readToken() == "")
        reopened.clearCredentials()
        "PASS native Keychain: add, reopen, update, delete, repeated delete"
    } finally {
        runCatching { store.clearCredentials() }
        preferences.removePersistentDomainForName(namespace)
    }
}
