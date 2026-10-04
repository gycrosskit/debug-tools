package com.dgtang.debugtools.bugreport

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import platform.CoreFoundation.CFDictionaryCreate
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRef
import platform.Foundation.NSData
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSUserDefaults
import platform.Foundation.create
import platform.Security.SecItemAdd
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecValueData
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.Ignore
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosBugReportStoreTest {
    @Test
    fun `reads existing history and shake keys without migration`() = runTest {
        val suite = "debug-tools-tests-${Random.nextLong()}"
        val preferences = NSUserDefaults(suiteName = suite)
        val store = IosBugReportStore(Json, suite, "test-token", "debug_bug_reporting", preferences)
        try {
            assertTrue(store.readShakeEnabled())
            val history = listOf(BugSubmissionRecord(42, "existing", 1_234, "https://example.invalid/42"))
            preferences.setBool(false, "debug_bug_reporting_shake_enabled")
            preferences.setObject(Json.encodeToString(history), "debug_bug_reporting_history")
            assertFalse(store.readShakeEnabled())
            assertEquals(history, store.readHistory())
            store.clearCredentials()
            assertFalse(store.readShakeEnabled())
            assertEquals(history, store.readHistory())
        } finally {
            preferences.removePersistentDomainForName(suite)
        }
    }

    @Test
    @Ignore // Gradle Native 独立 Simulator 进程返回 errSecNotAvailable；需在 App-hosted runner 启用。
    fun `token stays in injected keychain identity across store instances`() = runTest {
        val suite = "debug-tools-tests-${Random.nextLong()}"
        val preferences = NSUserDefaults(suiteName = suite)
        val store = IosBugReportStore(Json, suite, "test-token", "test", preferences)
        try {
            store.writeToken("fixture-token")
            val reopened = IosBugReportStore(Json, suite, "test-token", "test", preferences)
            assertEquals("fixture-token", reopened.readToken())
            assertEquals(null, preferences.stringForKey("zentao_token"))
            reopened.clearCredentials()
            assertEquals("", store.readToken())
        } finally {
            store.clearCredentials()
            preferences.removePersistentDomainForName(suite)
        }
    }

    @Test
    @Ignore // 与上面的 Token round-trip 一同在可访问 Keychain 的 App-hosted runner 验证。
    fun `corrupt token clears credentials and preserves history and settings`() = runTest {
        val suite = "debug-tools-tests-${Random.nextLong()}"
        val preferences = NSUserDefaults(suiteName = suite)
        val store = IosBugReportStore(Json, suite, "test-token", "test", preferences)
        try {
            val history = listOf(BugSubmissionRecord(42, "existing", 1_234, "https://example.invalid/42"))
            store.writeHistory(history)
            store.writeShakeEnabled(false)
            val invalidUtf8 = byteArrayOf(0xFF.toByte())
            val data = invalidUtf8.usePinned { NSData.create(bytes = it.addressOf(0), length = 1uL) }
            memScoped {
                val service = CFBridgingRetain(suite)
                val account = CFBridgingRetain("test-token")
                val tokenData = CFBridgingRetain(data)
                val keys = arrayOf<CFTypeRef?>(kSecClass, kSecAttrService, kSecAttrAccount, kSecValueData)
                val values = arrayOf<CFTypeRef?>(kSecClassGenericPassword, service, account, tokenData)
                val query = checkNotNull(CFDictionaryCreate(
                    null, allocArrayOf(*keys).reinterpret(), allocArrayOf(*values).reinterpret(), 4, null, null,
                ))
                try {
                    assertEquals(errSecSuccess, SecItemAdd(query, null))
                } finally {
                    CFRelease(query)
                    if (tokenData != null) CFRelease(tokenData)
                    if (account != null) CFRelease(account)
                    if (service != null) CFRelease(service)
                }
            }
            assertEquals("", store.readToken())
            assertEquals("", store.readToken())
            assertFalse(store.readShakeEnabled())
            assertEquals(history, store.readHistory())
        } finally {
            store.clearCredentials()
            preferences.removePersistentDomainForName(suite)
        }
    }
}
