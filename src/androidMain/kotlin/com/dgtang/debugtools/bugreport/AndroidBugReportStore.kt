package com.dgtang.debugtools.bugreport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** 命名空间和 Keystore alias 由宿主注入，升级时继续读取既有凭据、开关和历史。 */
class AndroidBugReportStore(
    context: Context,
    private val json: Json,
    preferencesName: String,
    private val keyAlias: String,
    private val tokenKey: String = "zentao_token",
    private val shakeEnabledKey: String = "shake_enabled",
    private val historyKey: String = "submission_history",
    private val draftKey: String = "bug_draft",
    private val pendingKey: String = "pending_bugs",
) : BugReportStore, BugReportWorkspaceStore {
    private val preferences = context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    override suspend fun readToken(): String {
        val encrypted = preferences.getString(tokenKey, null) ?: return ""
        return runCatching { decrypt(encrypted) }.getOrElse {
            // 密钥失效或密文损坏只撤销授权；不影响用户开关和已提交的 Bug 索引。
            preferences.edit().remove(tokenKey).apply()
            ""
        }
    }

    override suspend fun writeToken(value: String) {
        preferences.edit().putString(tokenKey, encrypt(value)).apply()
    }

    override suspend fun readShakeEnabled(): Boolean = preferences.getBoolean(shakeEnabledKey, true)

    override suspend fun writeShakeEnabled(value: Boolean) {
        preferences.edit().putBoolean(shakeEnabledKey, value).apply()
    }

    override suspend fun readHistory(): List<BugSubmissionRecord> = runCatching {
        json.decodeFromString<List<BugSubmissionRecord>>(preferences.getString(historyKey, null).orEmpty())
    }.getOrDefault(emptyList())

    override suspend fun writeHistory(value: List<BugSubmissionRecord>) {
        preferences.edit().putString(historyKey, json.encodeToString(value)).apply()
    }

    override suspend fun readDraft(): BugReportDraft = preferences.getString(draftKey, null)?.let { json.decodeFromString<BugReportDraft>(it) } ?: BugReportDraft()
    override suspend fun writeDraft(value: BugReportDraft) = withContext(Dispatchers.IO) {
        check(preferences.edit().putString(draftKey, json.encodeToString(value)).commit()) { "Draft could not be saved" }
    }
    override suspend fun readPending(): List<PendingBugReport> = preferences.getString(pendingKey, null)?.let { json.decodeFromString<List<PendingBugReport>>(it) } ?: emptyList()
    override suspend fun writePending(value: List<PendingBugReport>) = withContext(Dispatchers.IO) {
        check(preferences.edit().putString(pendingKey, json.encodeToString(value)).commit()) { "Pending reports could not be saved" }
    }

    override suspend fun clearCredentials() {
        preferences.edit().remove(tokenKey).apply()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        return Base64.encodeToString(cipher.iv + cipher.doFinal(value.encodeToByteArray()), Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val payload = Base64.decode(value, Base64.NO_WRAP)
        require(payload.size > IV_LENGTH)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_LENGTH_BITS, payload.copyOfRange(0, IV_LENGTH)))
        return cipher.doFinal(payload.copyOfRange(IV_LENGTH, payload.size)).decodeToString()
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER).run {
            init(
                KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val TAG_LENGTH_BITS = 128
    }
}
