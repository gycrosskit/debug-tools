package com.dgtang.debugtools.bugreport

/**
 * 可选持久化工作区，旧 Token Store 无需伪造支持；宿主按同一命名空间串行调用。
 * 写入成功返回必须确认落盘，失败抛错；损坏 journal 必须报错，不得回退为空绕过防重。
 * 不拥有 scope/附件原件，不存自动证据或凭据；取消不保证撤销已开始的系统 I/O。
 */
interface BugReportWorkspaceStore {
    /** 读取用户草稿；缺失可返回新草稿，损坏须报告。 */
    suspend fun readDraft(): BugReportDraft
    /** 确认持久化完整草稿后返回，保持 reportId 不变。 */
    suspend fun writeDraft(value: BugReportDraft)
    /** 读取完整防重/恢复 journal；缺失为空，损坏不得静默清除 UNKNOWN。 */
    suspend fun readPending(): List<PendingBugReport>
    /** 确认完整 journal 替换落盘后返回，避免部分更新导致重复创建/上传。 */
    suspend fun writePending(value: List<PendingBugReport>)
}
