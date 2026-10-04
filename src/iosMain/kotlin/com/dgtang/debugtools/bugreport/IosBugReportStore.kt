package com.dgtang.debugtools.bugreport

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import platform.CoreFoundation.CFDictionaryCreate
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.Foundation.NSData
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSUserDefaults
import platform.Foundation.create
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData
import platform.posix.memcpy

/** 沿用宿主 Keychain service/account 和偏好命名空间；Token 不写入 NSUserDefaults。 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosBugReportStore(
    private val json: Json,
    private val keychainService: String,
    private val keychainAccount: String,
    preferencesNamespace: String,
    private val preferences: NSUserDefaults = NSUserDefaults.standardUserDefaults,
    private val shakeEnabledKey: String = "${preferencesNamespace}_shake_enabled",
    private val historyKey: String = "${preferencesNamespace}_history",
) : BugReportStore {
    override suspend fun readToken(): String = memScoped {
        val result = alloc<CFTypeRefVar>()
        val status = withKeychainQuery(includeResult = true) { SecItemCopyMatching(it, result.ptr) }
        // 系统暂时不可访问 Keychain 时不删除凭据，只在成功读取但数据损坏时清理。
        if (status != errSecSuccess) return@memScoped ""
        val data = CFBridgingRelease(result.value) as? NSData
        val token = runCatching {
            checkNotNull(data)
            val bytes = ByteArray(data.length.toInt())
            if (bytes.isNotEmpty()) bytes.usePinned { memcpy(it.addressOf(0), data.bytes, data.length) }
            bytes.decodeToString(throwOnInvalidSequence = true)
        }.getOrNull()
        if (token == null) {
            clearCredentials()
            ""
        } else token
    }

    override suspend fun writeToken(value: String) {
        clearCredentials()
        val bytes = value.encodeToByteArray()
        val data = bytes.usePinned {
            NSData.create(bytes = if (bytes.isEmpty()) null else it.addressOf(0), length = bytes.size.toULong())
        }
        check(withKeychainQuery(data = data) { SecItemAdd(it, null) } == errSecSuccess) { "无法保存 Bug 报告 Token" }
    }

    override suspend fun readShakeEnabled(): Boolean = if (preferences.objectForKey(shakeEnabledKey) == null) {
        true
    } else preferences.boolForKey(shakeEnabledKey)

    override suspend fun writeShakeEnabled(value: Boolean) {
        preferences.setBool(value, shakeEnabledKey)
    }

    override suspend fun readHistory(): List<BugSubmissionRecord> = runCatching {
        json.decodeFromString<List<BugSubmissionRecord>>(preferences.stringForKey(historyKey).orEmpty())
    }.getOrDefault(emptyList())

    override suspend fun writeHistory(value: List<BugSubmissionRecord>) {
        preferences.setObject(json.encodeToString(value), historyKey)
    }

    override suspend fun clearCredentials() {
        withKeychainQuery { SecItemDelete(it) }
    }

    private inline fun <T> withKeychainQuery(
        includeResult: Boolean = false,
        data: NSData? = null,
        operation: (CFDictionaryRef) -> T,
    ): T = memScoped {
        val service = CFBridgingRetain(keychainService)
        val account = CFBridgingRetain(keychainAccount)
        val tokenData = CFBridgingRetain(data)
        try {
            val keys = mutableListOf<CFTypeRef?>(kSecClass, kSecAttrService, kSecAttrAccount)
            val values = mutableListOf<CFTypeRef?>(kSecClassGenericPassword, service, account)
            if (includeResult) {
                keys += listOf(kSecReturnData, kSecMatchLimit)
                values += listOf(kCFBooleanTrue, kSecMatchLimitOne)
            }
            if (data != null) {
                keys += kSecValueData
                values += tokenData
            }
            // Kotlin Map 不能 cast 成 CFDictionaryRef；字典使用本次调用期间存活的原生指针。
            val query = checkNotNull(CFDictionaryCreate(
                null,
                allocArrayOf(*keys.toTypedArray()).reinterpret(),
                allocArrayOf(*values.toTypedArray()).reinterpret(),
                keys.size.toLong(),
                null,
                null,
            ))
            try {
                operation(query)
            } finally {
                CFRelease(query)
            }
        } finally {
            if (tokenData != null) CFRelease(tokenData)
            if (account != null) CFRelease(account)
            if (service != null) CFRelease(service)
        }
    }
}
