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
import platform.Foundation.NSFileManager
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSUserDomainMask
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.writeToFile
import platform.Foundation.NSData
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSUserDefaults
import platform.Foundation.create
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecItemUpdate
import platform.Security.errSecItemNotFound
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

/**
 * 宿主串行使用的 iOS Store，所有方法在调用线程同步执行，无后台 scope；宿主后台调用文件/Keychain I/O。
 * 沿用既有 Keychain identity，Token 不写偏好；系统暂不可访问返回空但不删除，坏 UTF-8 只清凭据。
 * 草稿/journal 在私有目录原子写入且排除备份，损坏读取报错，不重置 UNKNOWN；不持有长期文件句柄。
 * @param json 与既有记录兼容的序列化配置。
 * @param keychainService 原宿主 Keychain service，测试须独立命名空间。
 * @param keychainAccount 原宿主 Keychain account，勿跨品牌共用。
 * @param preferencesNamespace 用户开关/历史键及默认工作区目录的隔离名称。
 * @param preferences 宿主偏好域，不由本组件关闭或清空。
 * @param shakeEnabledKey 用户开关键，缺失默认开启。
 * @param historyKey 历史 JSON 键，损坏读取为空。
 * @param draftKey 草稿文件逻辑键，经 UTF-8 十六进制编码后作文件名。
 * @param pendingKey journal 文件逻辑键，损坏不得回退为空。
 * @param workspaceDirectory 专属 Application Support 绝对目录，宿主负责保留/清理记录。
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosBugReportStore(
    private val json: Json,
    private val keychainService: String,
    private val keychainAccount: String,
    preferencesNamespace: String,
    private val preferences: NSUserDefaults = NSUserDefaults.standardUserDefaults,
    private val shakeEnabledKey: String = "${preferencesNamespace}_shake_enabled",
    private val historyKey: String = "${preferencesNamespace}_history",
    private val draftKey: String = "${preferencesNamespace}_draft",
    private val pendingKey: String = "${preferencesNamespace}_pending",
    private val workspaceDirectory: String = checkNotNull((NSFileManager.defaultManager.URLsForDirectory(NSApplicationSupportDirectory, NSUserDomainMask).first() as NSURL).path) +
        "/gycrosskit-debug-tools/" + preferencesNamespace.encodeToByteArray().joinToString("") { it.toUByte().toString(16).padStart(2, '0') },
) : BugReportStore, BugReportWorkspaceStore {
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
        val bytes = value.encodeToByteArray()
        val data = bytes.usePinned {
            NSData.create(bytes = if (bytes.isEmpty()) null else it.addressOf(0), length = bytes.size.toULong())
        }
        // 替换失败保留旧 Token；不能先 Delete 再 Add，系统暂不可用时会丢失已有授权。
        val updated = withKeychainQuery(data = data, identity = false) { attributes ->
            withKeychainQuery { query -> SecItemUpdate(query, attributes) }
        }
        val status = if (updated == errSecItemNotFound) withKeychainQuery(data = data) { SecItemAdd(it, null) } else updated
        check(status == errSecSuccess) { "无法保存 Bug 报告 Token ($status)" }
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

    override suspend fun readDraft(): BugReportDraft = readWorkspace(draftKey)?.let { json.decodeFromString<BugReportDraft>(it) } ?: BugReportDraft()
    override suspend fun writeDraft(value: BugReportDraft) = writeWorkspace(draftKey, json.encodeToString(value))
    override suspend fun readPending(): List<PendingBugReport> = readWorkspace(pendingKey)?.let { json.decodeFromString<List<PendingBugReport>>(it) } ?: emptyList()
    override suspend fun writePending(value: List<PendingBugReport>) = writeWorkspace(pendingKey, json.encodeToString(value))

    private fun readWorkspace(key: String): String? {
        val path = "$workspaceDirectory/${fileName(key)}.json"
        if (!NSFileManager.defaultManager.fileExistsAtPath(path)) return null
        val data = checkNotNull(NSData.create(contentsOfFile = path)) { "Workspace could not be read" }
        val bytes = ByteArray(data.length.toInt())
        if (bytes.isNotEmpty()) bytes.usePinned { memcpy(it.addressOf(0), data.bytes, data.length) }
        return bytes.decodeToString(throwOnInvalidSequence = true)
    }
    private fun writeWorkspace(key: String, text: String) {
        check(NSFileManager.defaultManager.createDirectoryAtPath(workspaceDirectory, true, null, null))
        check(NSURL.fileURLWithPath(workspaceDirectory).setResourceValue(true, NSURLIsExcludedFromBackupKey, null))
        val bytes = text.encodeToByteArray()
        val data = bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
        check(data.writeToFile("$workspaceDirectory/${fileName(key)}.json", atomically = true)) { "Workspace could not be saved" }
    }
    private fun fileName(key: String) = key.encodeToByteArray().joinToString("") { it.toUByte().toString(16).padStart(2, '0') }

    override suspend fun clearCredentials() {
        val status = withKeychainQuery { SecItemDelete(it) }
        check(status == errSecSuccess || status == errSecItemNotFound) { "无法清除 Bug 报告 Token ($status)" }
    }

    private inline fun <T> withKeychainQuery(
        includeResult: Boolean = false,
        data: NSData? = null,
        identity: Boolean = true,
        operation: (CFDictionaryRef) -> T,
    ): T = memScoped {
        val service = CFBridgingRetain(keychainService)
        val account = CFBridgingRetain(keychainAccount)
        val tokenData = CFBridgingRetain(data)
        try {
            val keys = mutableListOf<CFTypeRef?>()
            val values = mutableListOf<CFTypeRef?>()
            if (identity) {
                keys += listOf(kSecClass, kSecAttrService, kSecAttrAccount)
                values += listOf(kSecClassGenericPassword, service, account)
            }
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
