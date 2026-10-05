package io.github.gycrosskit.debugtools.consumer
import com.dgtang.debugtools.kuikly.DebugToolsModule
import com.dgtang.debugtools.bugreport.BugReportRepository
import com.dgtang.debugtools.bugreport.BugReportDraft
import com.dgtang.debugtools.bugreport.PendingBugReport

suspend fun consumeOhos(module: DebugToolsModule, repository: BugReportRepository): List<PendingBugReport> {
    module.configure("test-debug", "test-key")
    module.writeDraft(BugReportDraft(title = "test"))
    module.startShake()
    module.stopShake()
    return repository.pending()
}
