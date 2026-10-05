package com.dgtang.debugtools.bugreport

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * 提交、授权设置和本机历史入口，工作区操作使用实例 Mutex；账号密码不持久化。
 * 宿主串行调用授权/设置/历史操作，并遵守平台 Store 的线程约束；实例不拥有 client 或附件原件。
 * 取消传播，但远端已成功后的 journal/历史确认使用 NonCancellable，避免重复 POST。
 * @param nowMillis 确认时的 Unix 毫秒时钟。
 * @param loadAttachment 宿主授权读取器，返回完整内容；必须自行释放文件句柄，不读取未授权 id。
 */
class BugReportRepository(
    private val client: ZentaoBugClient,
    private val store: BugReportStore,
    @OptIn(ExperimentalTime::class)
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val loadAttachment: (suspend (BugReportAttachment) -> ByteArray)? = null,
) {
    private val workspace = store as? BugReportWorkspaceStore
    private val workspaceMutex = Mutex()
    /** Store 是否真正实现确认落盘的工作区契约。 */
    val draftsAvailable: Boolean get() = workspace != null
    /** 目标、工作区与宿主读取器均支持附件，不证明当前文件权限可用。 */
    val attachmentsAvailable: Boolean get() = client.attachmentsAvailable && loadAttachment != null && workspace != null
    /** 是否配置静态目标，不代表服务端可达/权限有效。 */
    val available: Boolean
        get() = client.available

    /** 读取 Token 是否存在和用户开关，不发网络，不在结果暴露 Token。 */
    suspend fun settings(): BugReportSettings = BugReportSettings(
        tokenConfigured = store.readToken().isNotBlank(),
        shakeEnabled = store.readShakeEnabled(),
    )

    /** 只有 Token 交换及产品权限均成功才替换安全 Token；失败/取消不持久化账号密码。 */
    suspend fun authorize(account: String, password: String) {
        val token = client.authorize(account, password)
        client.testConnection(token)
        store.writeToken(token)
    }

    /** 保存用户开关，不负责传感器启停。 */
    suspend fun setShakeEnabled(enabled: Boolean) = store.writeShakeEnabled(enabled)

    /** 读取 Token 并核验目标产品权限，失败以中性 notice 报告。 */
    suspend fun testConnection() {
        client.testConnection(store.readToken())
    }

    /** 验证草稿，支持工作区时先落盘防重 journal 再创建；无工作区仅保持旧无附件路径。 */
    suspend fun submit(
        draft: BugReportDraft,
        context: BugReportContext,
        evidence: BugEvidenceSnapshot,
    ): BugSubmissionResult {
        validateDraft(draft)
        // 旧宿主 Store 不支持 workspace 时保留原无附件提交路径，不伪造持久化成功。
        if (workspace == null) return saveHistory(client.submit(store.readToken(), draft, context, evidence), draft)
        val pending = queueDraft(draft)
        return submitPending(pending.id, context, evidence)
    }

    private suspend fun saveHistory(result: BugSubmissionResult, draft: BugReportDraft): BugSubmissionResult = withContext(NonCancellable) { try {
            val updated = listOf(
                BugSubmissionRecord(
                    id = result.id,
                    title = draft.title.trim(),
                    submittedAtMillis = nowMillis(),
                    url = result.url,
                ),
            ) + store.readHistory().filterNot { it.id == result.id }
            store.writeHistory(updated.take(MAX_HISTORY))
            result.copy(reportId = draft.reportId)
        } catch (_: Exception) {
            // 历史只是本机索引；保留远端 ID 与链接，避免用户因落盘失败重提同一个 Bug。
            result.copy(historySaved = false, reportId = draft.reportId)
        } }

    /** 读取恢复草稿；不支持工作区时返回新草稿，损坏状态向调用方报告。 */
    suspend fun draft(): BugReportDraft = workspaceMutex.withLock { workspace?.readDraft() ?: BugReportDraft() }
    /** 验证限长/附件能力后确认草稿落盘，可保存空标题；无工作区报告失败。 */
    suspend fun saveDraft(draft: BugReportDraft) = workspaceMutex.withLock {
        validateDraft(draft)
        requireWorkspace().writeDraft(draft)
    }
    /** 返回队列快照；includeCompleted=false 隐藏完全确认的记录，不删除 journal。 */
    suspend fun pending(includeCompleted: Boolean = false): List<PendingBugReport> = workspaceMutex.withLock {
        workspace?.readPending()?.filter { includeCompleted || !it.complete() } ?: emptyList()
    }

    /** 只保存用户输入和附件标识；自动证据、Token 和密码不进入离线队列。 */
    suspend fun queueDraft(draft: BugReportDraft): PendingBugReport = workspaceMutex.withLock {
        validateDraft(draft)
        if (draft.title.isBlank()) throw BugReportException(BugReportNotice(BugReportMessage.TITLE_REQUIRED))
        val storage = requireWorkspace()
        val entries = storage.readPending()
        entries.firstOrNull { it.draft.reportId == draft.reportId }?.let {
            checkDestination(it)
            if (it.status != PendingBugStatus.READY) return@withLock it
            val updated = it.copy(draft = draft)
            storage.writePending(entries.replace(updated))
            return@withLock updated
        }
        check(entries.count { !it.complete() } < MAX_PENDING) { "Pending reports limit reached" }
        val entry = PendingBugReport(draft.reportId, client.destination, draft)
        storage.writePending(entries.filterNot { it.complete() } + entries.filter { it.complete() }.takeLast(MAX_HISTORY) + entry)
        entry
    }

    /** 显式移除本机 id，不删除服务端记录或宿主附件。 */
    suspend fun deletePending(id: String) = workspaceMutex.withLock {
        val storage = requireWorkspace()
        storage.writePending(storage.readPending().filterNot { it.id == id })
    }

    /** UNKNOWN 只允许用户核查确认未创建后重试；不会因重启、联网或重新授权自动重发。 */
    suspend fun submitPending(
        id: String,
        context: BugReportContext,
        evidence: BugEvidenceSnapshot,
        confirmedNotCreated: Boolean = false,
    ): BugSubmissionResult = workspaceMutex.withLock {
        val storage = requireWorkspace()
        val entries = storage.readPending()
        var entry = entries.single { it.id == id }
        checkDestination(entry)
        validateDraft(entry.draft)
        if (entry.status == PendingBugStatus.CREATED) {
            val created = checkNotNull(entry.created)
            return@withLock saveHistory(BugSubmissionResult(created.id, created.url,
                failedAttachments = entry.draft.attachments.filter { it.id !in entry.uploadedAttachments }), entry.draft)
        }
        if (entry.status == PendingBugStatus.UNKNOWN && !confirmedNotCreated) {
            throw BugReportException(BugReportNotice(BugReportMessage.UNKNOWN_RESULT))
        }
        val token = store.readToken()
        if (token.isBlank()) throw BugReportException(BugReportNotice(BugReportMessage.TOKEN_REQUIRED))
        // 必须先确认本机标记落盘，再发远端创建；超时、取消或丢失 ACK 后保留 UNKNOWN。
        entry = entry.copy(status = PendingBugStatus.UNKNOWN)
        storage.writePending(entries.replace(entry))
        val result = try { client.submit(token, entry.draft, context, evidence) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            val notice = (error as? BugReportException)?.notice
            if (notice?.code in setOf(BugReportMessage.TOKEN_REQUIRED, BugReportMessage.SUBMIT_DENIED)) {
                withContext(NonCancellable) { storage.writePending(entries.replace(entry.copy(status = PendingBugStatus.READY))) }
                throw error
            }
            throw BugReportException(BugReportNotice(BugReportMessage.UNKNOWN_RESULT), error)
        }
        entry = entry.copy(status = PendingBugStatus.CREATED,
            created = BugSubmissionRecord(result.id, entry.draft.title, nowMillis(), result.url))
        try {
            withContext(NonCancellable) {
                storage.writePending(entries.replace(entry))
                if (storage.readDraft().reportId == entry.draft.reportId) storage.writeDraft(BugReportDraft())
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // 远端 Bug 已创建，不能把落盘失败改成“提交失败”后再次 POST。
            return@withLock saveHistory(result.copy(workspaceSaved = false, failedAttachments = entry.draft.attachments), entry.draft)
        }
        val uploaded = uploadRemaining(entry, token, emptySet())
        saveHistory(uploaded, entry.draft)
    }

    /** 对已经确认创建的 ID 补传附件，绝不会再调用创建 Bug。未知上传结果需先人工核查。 */
    suspend fun retryAttachments(id: String, confirmedNotUploaded: Set<String> = emptySet()): BugSubmissionResult = workspaceMutex.withLock {
        val entry = requireWorkspace().readPending().single { it.id == id }
        checkDestination(entry)
        check(entry.status == PendingBugStatus.CREATED && entry.created != null)
        if ((entry.uncertainAttachments - confirmedNotUploaded).isNotEmpty()) {
            throw BugReportException(BugReportNotice(BugReportMessage.UNKNOWN_RESULT))
        }
        val token = store.readToken()
        if (token.isBlank()) throw BugReportException(BugReportNotice(BugReportMessage.TOKEN_REQUIRED))
        saveHistory(uploadRemaining(entry, token, confirmedNotUploaded), entry.draft)
    }

    /** 宿主让用户核查禅道后关联已创建 Bug；这里只记录，不发送写请求。 */
    suspend fun confirmCreated(id: String, bugId: Int) = workspaceMutex.withLock {
        val storage = requireWorkspace()
        val entries = storage.readPending()
        val entry = entries.single { it.id == id }
        checkDestination(entry)
        check(entry.status == PendingBugStatus.UNKNOWN)
        val result = client.existingBug(bugId)
        val workspaceSaved = try {
            withContext(NonCancellable) {
                storage.writePending(entries.replace(entry.copy(status = PendingBugStatus.CREATED,
                    created = BugSubmissionRecord(result.id, entry.draft.title, nowMillis(), result.url))))
                if (storage.readDraft().reportId == entry.draft.reportId) storage.writeDraft(BugReportDraft())
            }
            true
        } catch (_: Exception) { false }
        saveHistory(result.copy(workspaceSaved = workspaceSaved, failedAttachments = entry.draft.attachments), entry.draft)
    }

    /** 仅给 CREATED 的未知附件记录正 fileId；不发请求，用户必须先核查。 */
    suspend fun confirmAttachmentUploaded(id: String, attachmentId: String, fileId: Int) = workspaceMutex.withLock {
        require(fileId > 0)
        val storage = requireWorkspace()
        val entries = storage.readPending()
        val entry = entries.single { it.id == id }
        checkDestination(entry)
        check(entry.status == PendingBugStatus.CREATED && attachmentId in entry.uncertainAttachments)
        storage.writePending(entries.replace(entry.copy(uploadedAttachments = entry.uploadedAttachments + (attachmentId to fileId),
            uncertainAttachments = entry.uncertainAttachments - attachmentId)))
    }

    private suspend fun uploadRemaining(original: PendingBugReport, token: String, confirmedNotUploaded: Set<String>): BugSubmissionResult {
        val storage = requireWorkspace()
        val created = checkNotNull(original.created)
        var entry = original.copy(uncertainAttachments = original.uncertainAttachments - confirmedNotUploaded)
        var workspaceSaved = true
        var attachmentNotice: BugReportNotice? = null
        suspend fun persist(value: PendingBugReport) {
            try { withContext(NonCancellable) { storage.writePending(storage.readPending().replace(value)) } }
            catch (error: Exception) { workspaceSaved = false; throw error }
        }
        for (attachment in entry.draft.attachments) {
            if (attachment.id in entry.uploadedAttachments || attachment.id in entry.uncertainAttachments) continue
            try {
                // 读取本地文件失败不会发 HTTP；只有准备发送后才标记结果未知。
                val bytes = checkNotNull(loadAttachment).invoke(attachment)
                require(bytes.size.toLong() == attachment.size)
                entry = entry.copy(uncertainAttachments = entry.uncertainAttachments + attachment.id)
                persist(entry)
                val fileId = client.uploadAttachment(token, created.id, attachment, bytes)
                entry = entry.copy(uncertainAttachments = entry.uncertainAttachments - attachment.id,
                    uploadedAttachments = entry.uploadedAttachments + (attachment.id to fileId))
                persist(entry)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                attachmentNotice = (error as? BugReportException)?.notice
                if (attachmentNotice?.code in setOf(BugReportMessage.TOKEN_REQUIRED, BugReportMessage.SUBMIT_DENIED)) {
                    entry = entry.copy(uncertainAttachments = entry.uncertainAttachments - attachment.id)
                    try { persist(entry) } catch (_: Exception) { workspaceSaved = false }
                    break
                }
                // 已创建 Bug 保持成功；未知附件结果保持 UNKNOWN，不隐式重传。
            }
        }
        val failed = entry.draft.attachments.filter { it.id !in entry.uploadedAttachments }
        return BugSubmissionResult(created.id, created.url, failedAttachments = failed, workspaceSaved = workspaceSaved, attachmentNotice = attachmentNotice)
    }

    private fun validateDraft(draft: BugReportDraft) {
        require(draft.reportId.isNotBlank() && draft.reportId.length <= 128)
        require(draft.title.length <= 120 && draft.steps.length <= 4_000 && draft.actualResult.length <= 4_000 && draft.expectedResult.length <= 4_000)
        require(draft.attachments.size <= 3 && draft.attachments.map { it.id }.distinct().size == draft.attachments.size)
        if (draft.attachments.isNotEmpty()) check(attachmentsAvailable) { "Attachment support is not configured" }
    }
    private fun requireWorkspace(): BugReportWorkspaceStore = workspace
        ?: throw BugReportException(BugReportNotice(BugReportMessage.WORKSPACE_UNAVAILABLE))
    private fun checkDestination(entry: PendingBugReport) {
        if (entry.destination != client.destination) throw BugReportException(BugReportNotice(BugReportMessage.WRONG_DESTINATION))
    }
    private fun PendingBugReport.complete() = status == PendingBugStatus.CREATED && draft.attachments.all { it.id in uploadedAttachments }
    private fun List<PendingBugReport>.replace(entry: PendingBugReport) = map { if (it.id == entry.id) entry else it }

    /** 读取本机索引，不查询服务端，不隐式发送队列。 */
    suspend fun history(): List<BugSubmissionRecord> = store.readHistory()

    /** 只清安全 Token，保留用户开关、历史及工作区。 */
    suspend fun clearCredentials() = store.clearCredentials()

    private companion object {
        const val MAX_HISTORY = 20
        const val MAX_PENDING = 10
    }
}
