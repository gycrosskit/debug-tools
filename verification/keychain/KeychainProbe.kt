@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package com.dgtang.debugtools.verification

import com.dgtang.debugtools.bugreport.IosBugReportStore
import com.dgtang.debugtools.bugreport.BugReportDraft
import com.dgtang.debugtools.bugreport.BugSubmissionRecord
import com.dgtang.debugtools.bugreport.PendingBugReport
import com.dgtang.debugtools.bugreport.PendingBugStatus
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import platform.Foundation.NSUserDefaults
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory

/** 只用于独立 Simulator App；不读取宿主命名空间或真实凭据。 */
fun keychainReplacementCheck(): String = runBlocking {
    val namespace = "gycrosskit-review-keychain-20261008"
    val preferences = NSUserDefaults(suiteName = namespace)
    val directory = NSTemporaryDirectory() + namespace
    val store = IosBugReportStore(Json, namespace, "fixture", namespace, preferences, workspaceDirectory = directory)
    val history = listOf(BugSubmissionRecord(42, "fixture", 1234, "https://example.invalid/42"))
    val draft = BugReportDraft(title = "Isolated Keychain fixture")
    val pending = listOf(PendingBugReport(draft.reportId, "fixture-target", draft, PendingBugStatus.UNKNOWN))
    try {
        store.clearCredentials()
        check(store.readToken() == "")
        store.writeHistory(history)
        store.writeShakeEnabled(false)
        store.writeDraft(draft)
        store.writePending(pending)
        store.writeToken("fixture-one")
        val reopened = IosBugReportStore(Json, namespace, "fixture", namespace, preferences, workspaceDirectory = directory)
        check(reopened.readToken() == "fixture-one")
        reopened.writeToken("fixture-two")
        check(store.readToken() == "fixture-two")
        reopened.clearCredentials()
        check(store.readToken() == "")
        check(reopened.readHistory() == history)
        check(!reopened.readShakeEnabled())
        check(reopened.readDraft() == draft)
        check(reopened.readPending() == pending)
        reopened.clearCredentials()
        "PASS native Keychain: add, reopen, update, delete, repeated delete; history, settings, draft and pending preserved"
    } finally {
        runCatching { store.clearCredentials() }
        NSFileManager.defaultManager.removeItemAtPath(directory, null)
        preferences.removePersistentDomainForName(namespace)
    }
}
