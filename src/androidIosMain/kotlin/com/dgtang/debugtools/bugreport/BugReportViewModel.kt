package com.dgtang.debugtools.bugreport

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope

/**
 * Android/iOS 的页面 ViewModel，操作线程/契约见 [BugReportController]，由 ViewModelStore 释放 scope。
 * 离页前由宿主调用 flushDraft；client/平台传感器另由宿主停止和释放。
 */
class BugReportViewModel(repository: BugReportRepository, hostDataSource: BugReportHostDataSource,
    formatMessage: (BugReportNotice) -> String = { it.text() },
) : ViewModel() {
    /** 共用状态机，使用本 ViewModel 的生命周期 scope，不得供其他页面复用。 */
    val controller = BugReportController(viewModelScope, repository, hostDataSource, formatMessage)
    /** 页面只读状态；契约见 [BugReportController.state]。 */
    val state = controller.state
    /** @see BugReportController.openForm */
    fun openForm() = controller.openForm()
    /** @see BugReportController.openSettings */
    fun openSettings() = controller.openSettings()
    /** @see BugReportController.openHistory */
    fun openHistory() = controller.openHistory()
    /** @see BugReportController.closeSection */
    fun closeSection() = controller.closeSection()
    /** @see BugReportController.updateTitle */
    fun updateTitle(value: String) = controller.updateTitle(value)
    /** @see BugReportController.updateSteps */
    fun updateSteps(value: String) = controller.updateSteps(value)
    /** @see BugReportController.updateActual */
    fun updateActual(value: String) = controller.updateActual(value)
    /** @see BugReportController.updateExpected */
    fun updateExpected(value: String) = controller.updateExpected(value)
    /** @see BugReportController.updateScope */
    fun updateScope(value: BugReportScope) = controller.updateScope(value)
    /** @see BugReportController.setShakeEnabled */
    fun setShakeEnabled(enabled: Boolean, onApplied: (Boolean) -> Unit) = controller.setShakeEnabled(enabled, onApplied)
    /** @see BugReportController.authorize */
    fun authorize(account: String, password: String) = controller.authorize(account, password)
    /** @see BugReportController.testConnection */
    fun testConnection() = controller.testConnection()
    /** @see BugReportController.clearCredentials */
    fun clearCredentials() = controller.clearCredentials()
    /** @see BugReportController.submit */
    fun submit() = controller.submit()
    /** @see BugReportController.updateAttachments */
    fun updateAttachments(value: List<BugReportAttachment>) = controller.updateAttachments(value)
    /** @see BugReportController.saveDraft */
    fun saveDraft() = controller.saveDraft()
    /** @see BugReportController.queueDraft */
    fun queueDraft() = controller.queueDraft()
    /** @see BugReportController.flushDraft */
    suspend fun flushDraft() = controller.flushDraft()
    /** @see BugReportController.submitPending */
    fun submitPending(id: String, confirmedNotCreated: Boolean = false) = controller.submitPending(id, confirmedNotCreated)
    /** @see BugReportController.retryAttachments */
    fun retryAttachments(id: String, confirmedNotUploaded: Set<String> = emptySet()) = controller.retryAttachments(id, confirmedNotUploaded)
    /** @see BugReportController.deletePending */
    fun deletePending(id: String) = controller.deletePending(id)
    /** @see BugReportController.confirmCreated */
    fun confirmCreated(id: String, bugId: Int) = controller.confirmCreated(id, bugId)
    /** @see BugReportController.confirmAttachmentUploaded */
    fun confirmAttachmentUploaded(id: String, attachmentId: String, fileId: Int) = controller.confirmAttachmentUploaded(id, attachmentId, fileId)
}
