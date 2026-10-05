package com.dgtang.debugtools.bugreport

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope

/** Android/iOS 保留原 ViewModel API；Kuikly 使用同一状态机并注入所属页面 scope。 */
class BugReportViewModel(repository: BugReportRepository, hostDataSource: BugReportHostDataSource,
    formatMessage: (BugReportNotice) -> String = { it.text() },
) : ViewModel() {
    val controller = BugReportController(viewModelScope, repository, hostDataSource, formatMessage)
    val state = controller.state
    fun openForm() = controller.openForm()
    fun openSettings() = controller.openSettings()
    fun openHistory() = controller.openHistory()
    fun closeSection() = controller.closeSection()
    fun updateTitle(value: String) = controller.updateTitle(value)
    fun updateSteps(value: String) = controller.updateSteps(value)
    fun updateActual(value: String) = controller.updateActual(value)
    fun updateExpected(value: String) = controller.updateExpected(value)
    fun updateScope(value: BugReportScope) = controller.updateScope(value)
    fun setShakeEnabled(enabled: Boolean, onApplied: (Boolean) -> Unit) = controller.setShakeEnabled(enabled, onApplied)
    fun authorize(account: String, password: String) = controller.authorize(account, password)
    fun testConnection() = controller.testConnection()
    fun clearCredentials() = controller.clearCredentials()
    fun submit() = controller.submit()
    fun updateAttachments(value: List<BugReportAttachment>) = controller.updateAttachments(value)
    fun saveDraft() = controller.saveDraft()
    fun queueDraft() = controller.queueDraft()
    suspend fun flushDraft() = controller.flushDraft()
    fun submitPending(id: String, confirmedNotCreated: Boolean = false) = controller.submitPending(id, confirmedNotCreated)
    fun retryAttachments(id: String, confirmedNotUploaded: Set<String> = emptySet()) = controller.retryAttachments(id, confirmedNotUploaded)
    fun deletePending(id: String) = controller.deletePending(id)
    fun confirmCreated(id: String, bugId: Int) = controller.confirmCreated(id, bugId)
    fun confirmAttachmentUploaded(id: String, attachmentId: String, fileId: Int) = controller.confirmAttachmentUploaded(id, attachmentId, fileId)
}
