package com.dgtang.debugtools.bugreport

import kotlinx.coroutines.CancellationException
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** 提交、授权设置和本机历史的统一入口；账号密码不持久化，平台 Store 只安全保存 Token。 */
class BugReportRepository(
    private val client: ZentaoBugClient,
    private val store: BugReportStore,
    @OptIn(ExperimentalTime::class)
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    val available: Boolean
        get() = client.available

    suspend fun settings(): BugReportSettings = BugReportSettings(
        tokenConfigured = store.readToken().isNotBlank(),
        shakeEnabled = store.readShakeEnabled(),
    )

    suspend fun authorize(account: String, password: String) {
        val token = client.authorize(account, password)
        client.testConnection(token)
        store.writeToken(token)
    }

    suspend fun setShakeEnabled(enabled: Boolean) = store.writeShakeEnabled(enabled)

    suspend fun testConnection() {
        client.testConnection(store.readToken())
    }

    suspend fun submit(
        draft: BugReportDraft,
        context: BugReportContext,
        evidence: BugEvidenceSnapshot,
    ): BugSubmissionResult {
        val result = client.submit(store.readToken(), draft, context, evidence)
        return try {
            val updated = listOf(
                BugSubmissionRecord(
                    id = result.id,
                    title = draft.title.trim(),
                    submittedAtMillis = nowMillis(),
                    url = result.url,
                ),
            ) + store.readHistory()
            store.writeHistory(updated.take(MAX_HISTORY))
            result
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // 历史只是本机索引；保留远端 ID 与链接，避免用户因落盘失败重提同一个 Bug。
            result.copy(historySaved = false)
        }
    }

    suspend fun history(): List<BugSubmissionRecord> = store.readHistory()

    suspend fun clearCredentials() = store.clearCredentials()

    private companion object {
        const val MAX_HISTORY = 20
    }
}
