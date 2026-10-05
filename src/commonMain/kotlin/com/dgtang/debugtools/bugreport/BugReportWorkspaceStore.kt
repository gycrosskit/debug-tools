package com.dgtang.debugtools.bugreport

/** 与旧 Token Store 分开，已有宿主实现无需伪造草稿持久化支持。原生三端实现同时提供此能力。 */
interface BugReportWorkspaceStore {
    suspend fun readDraft(): BugReportDraft
    suspend fun writeDraft(value: BugReportDraft)
    suspend fun readPending(): List<PendingBugReport>
    suspend fun writePending(value: List<PendingBugReport>)
}
