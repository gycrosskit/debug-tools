package com.dgtang.debugtools.bugreport

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
)

/** 可迁移的 Bug 提交状态机；应用上下文统一通过 [BugReportHostDataSource] 注入。 */
class BugReportViewModel(
    private val repository: BugReportRepository,
    private val hostDataSource: BugReportHostDataSource,
) : ViewModel() {
    private val mutableState = MutableStateFlow(BugReportUiState())
    private var automaticEvidence = BugEvidenceSnapshot()
    val state = mutableState.asStateFlow()

    init {
        refresh()
    }

    fun openForm() {
        if (!repository.available) {
            mutableState.update { it.copy(message = "当前品牌尚未开通禅道 Bug 提交", isError = true) }
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

    fun setShakeEnabled(enabled: Boolean, onApplied: (Boolean) -> Unit) {
        runOperation(successMessage = if (enabled) "摇一摇提 Bug 已开启" else "摇一摇提 Bug 已关闭") {
            repository.setShakeEnabled(enabled)
            mutableState.update { it.copy(shakeEnabled = enabled) }
            onApplied(enabled)
        }
    }

    fun authorize(account: String, password: String) {
        runOperation(successMessage = "禅道账号授权成功") {
            repository.authorize(account, password)
            mutableState.update { it.copy(tokenConfigured = true) }
        }
    }

    fun testConnection() {
        runOperation(successMessage = "禅道连接成功") {
            repository.testConnection()
        }
    }

    fun clearCredentials() {
        runOperation(successMessage = "禅道登录信息已清除") {
            repository.clearCredentials()
            mutableState.update { it.copy(tokenConfigured = false) }
        }
    }

    fun submit() {
        if (!repository.available) {
            mutableState.update { it.copy(message = "当前品牌尚未开通禅道 Bug 提交", isError = true) }
            return
        }
        val current = mutableState.value
        if (current.draft.title.isBlank()) {
            mutableState.update { it.copy(message = "请填写 Bug 标题", isError = true) }
            return
        }
        val evidence = automaticEvidence
        runOperation(successMessage = "") {
            val result = repository.submit(
                draft = current.draft,
                context = hostDataSource.captureContext(),
                evidence = evidence,
            )
            mutableState.update {
                it.copy(
                    message = if (result.historySaved) {
                        "Bug #${result.id} 提交成功"
                    } else {
                        "Bug #${result.id} 提交成功，本机历史未保存"
                    },
                    isError = false,
                    submittedUrl = result.url,
                    draft = BugReportDraft(),
                )
            }
            if (result.historySaved) {
                try {
                    val history = repository.history()
                    mutableState.update { it.copy(history = history) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    mutableState.update {
                        it.copy(message = "Bug #${result.id} 提交成功，本机历史刷新失败")
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
                mutableState.update {
                    it.copy(
                        loading = false,
                        tokenConfigured = settings.tokenConfigured,
                        shakeEnabled = settings.shakeEnabled,
                        history = history,
                    )
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                mutableState.update {
                    it.copy(loading = false, message = error.displayMessage(), isError = true)
                }
            }
        }
    }

    private fun updateDraft(transform: BugReportDraft.() -> BugReportDraft) {
        mutableState.update { state ->
            if (state.working) state else state.copy(draft = state.draft.transform(), message = "", submittedUrl = "")
        }
    }

    /** 日志尾部读取不阻塞 UI；证据仅保留在状态机内部，不在测试表单展示。 */
    private fun loadEvidence() {
        runOperation(successMessage = "") {
            automaticEvidence = hostDataSource.captureEvidence()
            mutableState.update { it.copy(message = "", submittedUrl = "") }
        }
    }

    private fun runOperation(successMessage: String, operation: suspend () -> Unit) {
        if (mutableState.value.working) return
        mutableState.update { it.copy(working = true, message = "", isError = false) }
        viewModelScope.launch {
            try {
                operation()
                if (successMessage.isNotBlank()) {
                    mutableState.update { it.copy(message = successMessage, isError = false) }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                mutableState.update { it.copy(message = error.displayMessage(), isError = true) }
            } finally {
                mutableState.update { it.copy(working = false) }
            }
        }
    }
}

private fun Throwable.displayMessage(): String = message?.takeIf(String::isNotBlank) ?: "操作失败，请稍后重试"
