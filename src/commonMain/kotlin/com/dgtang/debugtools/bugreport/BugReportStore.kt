package com.dgtang.debugtools.bugreport

/** Token 必须由平台安全存储实现；公共模块不接触 MMKV、SharedPreferences 或 Keychain 类型。 */
interface BugReportStore {
    suspend fun readToken(): String
    suspend fun writeToken(value: String)
    suspend fun readShakeEnabled(): Boolean
    suspend fun writeShakeEnabled(value: Boolean)
    suspend fun readHistory(): List<BugSubmissionRecord>
    suspend fun writeHistory(value: List<BugSubmissionRecord>)
    suspend fun clearCredentials()
}
