package com.dgtang.debugtools.bugreport

import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class BugReportSection { HOME, FORM, SETTINGS, HISTORY }

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

/** 共用提交状态机；操作和 scope 必须属于宿主同一串行 UI/Page Context。 */
class BugReportController(
    private val viewModelScope: CoroutineScope,
    private val repository: BugReportRepository,
    private val hostDataSource: BugReportHostDataSource,
    private val formatMessage: (BugReportNotice) -> String = { it.text() },
) {
    private val mutableState = MutableStateFlow(BugReportUiState())
    private var automaticEvidence = BugEvidenceSnapshot()
    private var draftSaveJob: Job? = null
    val state = mutableState.asStateFlow()

    init {
        refresh()
    }

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

    fun openSettings() = mutableState.update {
        it.copy(section = BugReportSection.SETTINGS, message = "")
    }

    fun openHistory() = mutableState.update {
        it.copy(section = BugReportSection.HISTORY, message = "")
    }

    fun closeSection() = mutableState.update {
        it.copy(section = BugReportSection.HOME, message = "")
    }

    fun updateTitle(value: String) = updateDraft { copy(title = value.take(120)) }
    fun updateSteps(value: String) = updateDraft { copy(steps = value.take(4_000)) }
    fun updateActual(value: String) = updateDraft { copy(actualResult = value.take(4_000)) }
    fun updateExpected(value: String) = updateDraft { copy(expectedResult = value.take(4_000)) }
    fun updateScope(value: BugReportScope) = updateDraft { copy(scope = value) }
    fun updateAttachments(value: List<BugReportAttachment>) {
        require(value.size <= 3 && value.map { it.id }.distinct().size == value.size)
        check(value.isEmpty() || repository.attachmentsAvailable)
        updateDraft { copy(attachments = value) }
    }
    suspend fun flushDraft() {
        draftSaveJob?.join()
        repository.saveDraft(mutableState.value.draft)
    }
    fun saveDraft() = runOperation(BugReportNotice(BugReportMessage.DRAFT_SAVED)) {
        flushDraft()
    }
    fun queueDraft() = runOperation(BugReportNotice(BugReportMessage.PENDING_SAVED)) {
        repository.queueDraft(mutableState.value.draft)
        val pending = repository.pending()
        mutableState.update { it.copy(pending = pending) }
    }


    fun setShakeEnabled(enabled: Boolean, onApplied: (Boolean) -> Unit) {
        runOperation(successNotice = BugReportNotice(if (enabled) BugReportMessage.SHAKE_ENABLED else BugReportMessage.SHAKE_DISABLED)) {
            repository.setShakeEnabled(enabled)
            mutableState.update { it.copy(shakeEnabled = enabled) }
            onApplied(enabled)
        }
    }

    fun authorize(account: String, password: String) {
        runOperation(successNotice = BugReportNotice(BugReportMessage.AUTHORIZED)) {
            repository.authorize(account, password)
            mutableState.update { it.copy(tokenConfigured = true) }
        }
    }

    fun testConnection() {
        runOperation(successNotice = BugReportNotice(BugReportMessage.CONNECTION_OK)) {
            repository.testConnection()
        }
    }

    fun clearCredentials() {
        runOperation(successNotice = BugReportNotice(BugReportMessage.CREDENTIALS_CLEARED)) {
            repository.clearCredentials()
            mutableState.update { it.copy(tokenConfigured = false) }
        }
    }

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


    fun submitPending(id: String, confirmedNotCreated: Boolean = false) = runOperation(null) {
        val context = hostDataSource.captureContext()
        val evidence = hostDataSource.captureEvidence()
        showSubmission(repository.submitPending(id, context, evidence, confirmedNotCreated))
    }
    fun retryAttachments(id: String, confirmedNotUploaded: Set<String> = emptySet()) = runOperation(null) {
        showSubmission(repository.retryAttachments(id, confirmedNotUploaded))
    }
    fun deletePending(id: String) = runOperation(null) { repository.deletePending(id); refreshPending() }
    fun confirmCreated(id: String, bugId: Int) = runOperation(null) { showSubmission(repository.confirmCreated(id, bugId)) }
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
        if (mutableState.value.working) return
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
