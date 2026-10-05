package com.dgtang.debugtools.bugreport

import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 当前页面区域；切换不会自动发送 Bug 或清理草稿。 */
enum class BugReportSection { HOME, FORM, SETTINGS, HISTORY }

/**
 * 页面状态快照，不含凭据和自动日志原文。
 * @property section 当前区域。
 * @property loading 初始 Store 恢复尚未结束。
 * @property working 正在进行单个页面操作，阻止重复操作和输入覆盖。
 * @property tokenConfigured 安全存储存在 Token，不代表已核验权限。
 * @property shakeEnabled 用户开关，不替代宿主准入。
 * @property draft 当前用户输入，不含自动证据。
 * @property history 最新本机提交索引。
 * @property message 宿主 formatMessage 生成的显示文案。
 * @property isError 页面操作失败标记，已知创建成功不会被本机保存失败改写。
 * @property submittedUrl 已知创建结果链接。
 * @property notice 中性状态码。
 * @property pending 尚需提交/核查/补传的本机 journal。
 * @property failedAttachments 尚未确认上传的附件描述。
 * @property workspaceSaved 最近状态保存是否成功，失败时先核查服务端。
 * @property attachmentNotice 附件失败中性状态码。
 */
data class BugReportUiState(
    val section: BugReportSection = BugReportSection.HOME,
    val loading: Boolean = true,
    val working: Boolean = false,
    val tokenConfigured: Boolean = false,
    val shakeEnabled: Boolean = true,
    val draft: BugReportDraft = BugReportDraft(),
    val history: List<BugSubmissionRecord> = emptyList(),
    val message: String = "",
    val isError: Boolean = false,
    val submittedUrl: String = "",
    val notice: BugReportNotice? = null,
    val pending: List<PendingBugReport> = emptyList(),
    val failedAttachments: List<BugReportAttachment> = emptyList(),
    val workspaceSaved: Boolean = true,
    val attachmentNotice: BugReportNotice? = null,
)

/**
 * 共用提交状态机；方法及 scope 必须属于同一串行 UI/Page Context，实例不自行切线程。
 * 初始恢复期间忽略操作，避免旧快照覆盖新的授权/开关；宿主取消页面 scope 释放任务。
 * 离页前 flushDraft，停止生产后关闭专用 client/传感器。
 * @param viewModelScope 所属页面的 scope，调用方持有并取消；不可复用到其他页面。
 * @param formatMessage 中性 notice 的资源映射，同 UI Context 调用，默认中文。
 */
class BugReportController(
    private val viewModelScope: CoroutineScope,
    private val repository: BugReportRepository,
    private val hostDataSource: BugReportHostDataSource,
    private val formatMessage: (BugReportNotice) -> String = { it.text() },
) {
    private val mutableState = MutableStateFlow(BugReportUiState())
    private var automaticEvidence = BugEvidenceSnapshot()
    private var draftSaveJob: Job? = null
    /** 只读状态流；观察者与页面同生命周期，不含自动证据或凭据。 */
    val state = mutableState.asStateFlow()

    init {
        refresh()
    }

    /** 打开表单并异步读取一次自动证据；配置不可用时报告，不发提交请求。 */
    fun openForm() {
        if (!repository.available) {
            mutableState.update { it.copy(message = formatMessage(BugReportNotice(BugReportMessage.TARGET_UNAVAILABLE)), notice = BugReportNotice(BugReportMessage.TARGET_UNAVAILABLE), isError = true) }
            return
        }
        automaticEvidence = BugEvidenceSnapshot()
        mutableState.update {
            it.copy(
                section = BugReportSection.FORM,
                message = "",
                submittedUrl = "",
            )
        }
        loadEvidence()
    }

    /** 切换设置区，保留用户草稿。 */
    fun openSettings() = mutableState.update {
        it.copy(section = BugReportSection.SETTINGS, message = "")
    }

    /** 切换历史区，不重新发送待提交记录。 */
    fun openHistory() = mutableState.update {
        it.copy(section = BugReportSection.HISTORY, message = "")
    }

    /** 返回首页，保留工作区记录；不取消正在进行的厂商/网络操作。 */
    fun closeSection() = mutableState.update {
        it.copy(section = BugReportSection.HOME, message = "")
    }

    /** 更新标题并串行排队保存，保留前 120 个 UTF-16 字符；loading/working 时忽略。 */
    fun updateTitle(value: String) = updateDraft { copy(title = value.take(120)) }
    /** 更新步骤，保留前 4000 个字符；loading/working 时忽略。 */
    fun updateSteps(value: String) = updateDraft { copy(steps = value.take(4_000)) }
    /** 更新实际结果，保留前 4000 个字符；loading/working 时忽略。 */
    fun updateActual(value: String) = updateDraft { copy(actualResult = value.take(4_000)) }
    /** 更新期望结果，保留前 4000 个字符；loading/working 时忽略。 */
    fun updateExpected(value: String) = updateDraft { copy(expectedResult = value.take(4_000)) }
    /** 更新影响范围；loading/working 时忽略。 */
    fun updateScope(value: BugReportScope) = updateDraft { copy(scope = value) }
    /** 设置最多 3 个唯一 id 附件，非空须已启用附件能力；只保存描述，原件由宿主拥有。 */
    fun updateAttachments(value: List<BugReportAttachment>) {
        require(value.size <= 3 && value.map { it.id }.distinct().size == value.size)
        check(value.isEmpty() || repository.attachmentsAvailable)
        updateDraft { copy(attachments = value) }
    }
    /** 等待排队保存并确认当前草稿落盘；取消/失败向调用方传播，离页前由宿主调用。 */
    suspend fun flushDraft() {
        draftSaveJob?.join()
        repository.saveDraft(mutableState.value.draft)
    }
    /** 显式保存草稿并显示结果；working 时忽略重复操作。 */
    fun saveDraft() = runOperation(BugReportNotice(BugReportMessage.DRAFT_SAVED)) {
        flushDraft()
    }
    /** 只保存离线待提交，不采集证据或发网络写请求；用户之后手动提交。 */
    fun queueDraft() = runOperation(BugReportNotice(BugReportMessage.PENDING_SAVED)) {
        repository.queueDraft(mutableState.value.draft)
        val pending = repository.pending()
        mutableState.update { it.copy(pending = pending) }
    }


    /** 先保存用户开关，成功后在所属 UI Context 调用 onApplied；平台启停由宿主完成。 */
    fun setShakeEnabled(enabled: Boolean, onApplied: (Boolean) -> Unit) {
        runOperation(successNotice = BugReportNotice(if (enabled) BugReportMessage.SHAKE_ENABLED else BugReportMessage.SHAKE_DISABLED)) {
            repository.setShakeEnabled(enabled)
            mutableState.update { it.copy(shakeEnabled = enabled) }
            onApplied(enabled)
        }
    }

    /** 交换账号密码并核验产品权限后保存 Token；账号密码不进状态/工作区。 */
    fun authorize(account: String, password: String) {
        runOperation(successNotice = BugReportNotice(BugReportMessage.AUTHORIZED)) {
            repository.authorize(account, password)
            mutableState.update { it.copy(tokenConfigured = true) }
        }
    }

    /** 用已存 Token 核验目标产品权限，不创建 Bug。 */
    fun testConnection() {
        runOperation(successNotice = BugReportNotice(BugReportMessage.CONNECTION_OK)) {
            repository.testConnection()
        }
    }

    /** 仅清 Token 并更新页面标记，保留工作区/历史/用户开关。 */
    fun clearCredentials() {
        runOperation(successNotice = BugReportNotice(BugReportMessage.CREDENTIALS_CLEARED)) {
            repository.clearCredentials()
            mutableState.update { it.copy(tokenConfigured = false) }
        }
    }

    /** 提交当前草稿与捕获证据；working 时忽略重复，已知创建结果保持成功语义。 */
    fun submit() {
        if (!repository.available) {
            mutableState.update { it.copy(message = formatMessage(BugReportNotice(BugReportMessage.TARGET_UNAVAILABLE)), notice = BugReportNotice(BugReportMessage.TARGET_UNAVAILABLE), isError = true) }
            return
        }
        val current = mutableState.value
        if (current.draft.title.isBlank()) {
            mutableState.update { it.copy(message = formatMessage(BugReportNotice(BugReportMessage.TITLE_REQUIRED)), notice = BugReportNotice(BugReportMessage.TITLE_REQUIRED), isError = true) }
            return
        }
        val evidence = automaticEvidence
        runOperation(successNotice = null) {
            draftSaveJob?.join()
            val result = repository.submit(
                draft = current.draft,
                context = hostDataSource.captureContext(),
                evidence = evidence,
            )
            showSubmission(result)
        }
    }


    /** 显式提交队列 id；UNKNOWN 只有用户核查未创建后 confirmedNotCreated=true 才允许重试。 */
    fun submitPending(id: String, confirmedNotCreated: Boolean = false) = runOperation(null) {
        val context = hostDataSource.captureContext()
        val evidence = hostDataSource.captureEvidence()
        showSubmission(repository.submitPending(id, context, evidence, confirmedNotCreated))
    }
    /** 只补传已创建 Bug 的附件；confirmedNotUploaded 必须来自用户核查，不创建新 Bug。 */
    fun retryAttachments(id: String, confirmedNotUploaded: Set<String> = emptySet()) = runOperation(null) {
        showSubmission(repository.retryAttachments(id, confirmedNotUploaded))
    }
    /** 显式删除本机队列 id，不删除服务端 Bug 或宿主附件原件。 */
    fun deletePending(id: String) = runOperation(null) { repository.deletePending(id); refreshPending() }
    /** 用户核查后记录正 bugId，不发远端创建请求。 */
    fun confirmCreated(id: String, bugId: Int) = runOperation(null) { showSubmission(repository.confirmCreated(id, bugId)) }
    /** 用户核查后记录正 fileId，并移除该附件的 UNKNOWN，不发上传请求。 */
    fun confirmAttachmentUploaded(id: String, attachmentId: String, fileId: Int) = runOperation(null) {
        repository.confirmAttachmentUploaded(id, attachmentId, fileId); refreshPending()
    }
    private suspend fun refreshPending() {
        val pending = repository.pending()
        mutableState.update { it.copy(pending = pending) }
    }
    private suspend fun showSubmission(result: BugSubmissionResult) {
        val notice = BugReportNotice(when {
            !result.workspaceSaved -> BugReportMessage.SUBMITTED_LOCAL_STATE_FAILED
            result.failedAttachments.isNotEmpty() -> BugReportMessage.ATTACHMENT_FAILED
            !result.historySaved -> BugReportMessage.HISTORY_SAVE_FAILED
            else -> BugReportMessage.SUBMITTED
        }, bugId = result.id)
        mutableState.update {
            it.copy(
                notice = notice, message = formatMessage(notice) +
                    (result.attachmentNotice?.let { cause -> "; " + formatMessage(cause) } ?: ""),
                attachmentNotice = result.attachmentNotice,
                failedAttachments = result.failedAttachments, workspaceSaved = result.workspaceSaved,
                isError = false,
                submittedUrl = result.url,
                draft = if (result.reportId == it.draft.reportId) BugReportDraft() else it.draft,
            )
        }
        if (result.historySaved) {
            try {
                val history = repository.history()
                val pending = repository.pending()
                mutableState.update { it.copy(history = history, pending = pending) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update {
                    BugReportNotice(BugReportMessage.HISTORY_REFRESH_FAILED, bugId = result.id).let { notice ->
                        it.copy(notice = notice, message = formatMessage(notice))
                    }
                }
            }
        }
    }

    private fun refresh() {
        viewModelScope.launch {
            runCatching {
                val settings = repository.settings()
                val history = repository.history()
                val draft = repository.draft()
                val pending = repository.pending()
                mutableState.update {
                    it.copy(
                        loading = false,
                        tokenConfigured = settings.tokenConfigured,
                        shakeEnabled = settings.shakeEnabled,
                        history = history, draft = draft, pending = pending,
                    )
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                val notice = (error as? BugReportException)?.notice ?: BugReportNotice(BugReportMessage.OPERATION_FAILED)
                mutableState.update { it.copy(loading = false, notice = notice, message = formatMessage(notice), isError = true) }
            }
            // 摇动/深链可以在恢复期间打开表单；只为仍停留在表单的页面补采集一次。
            if (mutableState.value.section == BugReportSection.FORM) loadEvidence()
        }
    }

    private fun updateDraft(transform: BugReportDraft.() -> BugReportDraft) {
        val current = mutableState.value
        if (current.working || current.loading) return
        val draft = current.draft.transform()
        mutableState.value = current.copy(draft = draft, message = "", notice = null, submittedUrl = "")
        val previousSave = draftSaveJob
        if (repository.draftsAvailable) draftSaveJob = viewModelScope.launch {
            try { previousSave?.join(); repository.saveDraft(draft) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                val notice = BugReportNotice(BugReportMessage.WORKSPACE_SAVE_FAILED)
                mutableState.update { it.copy(notice = notice, message = formatMessage(notice), workspaceSaved = false) }
            }
        }
    }

    /** 日志尾部读取不阻塞 UI；证据仅保留在状态机内部，不在测试表单展示。 */
    private fun loadEvidence() {
        runOperation(successNotice = null) {
            automaticEvidence = hostDataSource.captureEvidence()
            mutableState.update { it.copy(message = "", submittedUrl = "") }
        }
    }

    private fun runOperation(successNotice: BugReportNotice?, operation: suspend () -> Unit) {
        if (mutableState.value.loading || mutableState.value.working) return
        mutableState.update { it.copy(working = true, message = "", isError = false) }
        viewModelScope.launch {
            try {
                operation()
                if (successNotice != null) {
                    mutableState.update { it.copy(notice = successNotice, message = formatMessage(successNotice), isError = false) }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                val notice = (error as? BugReportException)?.notice ?: BugReportNotice(BugReportMessage.OPERATION_FAILED)
                mutableState.update { it.copy(notice = notice, message = formatMessage(notice), isError = true) }
            } finally {
                mutableState.update { it.copy(working = false) }
            }
        }
    }
}
