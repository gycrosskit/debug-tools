package com.dgtang.debugtools.bugreport

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class BugReportDraftRecoveryTest {
    @Test fun flushWaitsForUndispatchedAndSuspendedInitialRecovery() = runTest {
        val gate = CompletableDeferred<Unit>()
        val store = RecoveryStore().apply { beforeHistory = { gate.await() } }
        val page = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val http = HttpClient(MockEngine { error("Recovery must not send HTTP") })
        val controller = controller(page, store, http)
        try {
            val saving = async(start = CoroutineStart.UNDISPATCHED) { controller.flushDraft() }
            assertFalse(saving.isCompleted)
            assertEquals(0, store.writes)
            runCurrent()
            assertTrue(controller.state.value.loading)
            assertFalse(saving.isCompleted)
            assertEquals(OLD, store.persisted)
            gate.complete(Unit)
            saving.await()
            assertEquals(OLD, store.persisted)
            controller.updateTitle("Edited after recovery")
            controller.flushDraft()
            assertEquals("Edited after recovery", store.persisted.title)
            assertEquals(OLD.reportId, store.persisted.reportId)
        } finally { gate.complete(Unit); page.cancel(); http.close() }
    }

    @Test fun failedRecoveryNeverWritesDefaultDraft() = runTest {
        for (phase in listOf("settings", "history", "draft", "pending")) {
            val store = RecoveryStore().apply { failingPhase = phase }
            val page = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            val http = HttpClient(MockEngine { error("Recovery must not send HTTP") })
            val controller = controller(page, store, http)
            try {
                runCurrent()
                assertFalse(controller.state.value.loading)
                assertTrue(controller.state.value.isError)
                assertFailsWith<IllegalStateException>(phase) { controller.flushDraft() }
                controller.updateTitle("Must not overwrite unread draft")
                controller.saveDraft()
                controller.queueDraft()
                controller.submit()
                controller.submitPending(OLD.reportId)
                controller.confirmCreated(OLD.reportId, 42)
                controller.deletePending(OLD.reportId)
                controller.retryAttachments(OLD.reportId)
                controller.confirmAttachmentUploaded(OLD.reportId, "file", 42)
                runCurrent()
                assertEquals(OLD, store.persisted, phase)
                assertEquals(0, store.writes, phase)
            } finally { page.cancel(); http.close() }
        }
    }

    @Test fun cancelledPageNeverPersistsAnUnreadOrStaleDraft() = runTest {
        for (phase in listOf("undispatched", "suspended", "restored")) {
            val gate = CompletableDeferred<Unit>()
            val store = RecoveryStore().apply { if (phase == "suspended") beforeHistory = { gate.await() } }
            val page = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            val http = HttpClient(MockEngine { error("Recovery must not send HTTP") })
            val controller = controller(page, store, http)
            try {
                if (phase != "undispatched") runCurrent()
                page.cancel()
                assertFailsWith<CancellationException>(phase) { controller.flushDraft() }
                assertEquals(OLD, store.persisted, phase)
                assertEquals(0, store.writes, phase)
            } finally { gate.complete(Unit); page.cancel(); http.close() }
        }
    }

    private fun controller(page: CoroutineScope, store: RecoveryStore, http: HttpClient) = BugReportController(
        page, BugReportRepository(ZentaoBugClient(http, BugReportTarget.Unavailable), store),
        object : BugReportHostDataSource {
            override suspend fun captureEvidence() = BugEvidenceSnapshot()
            override suspend fun captureContext() = error("Recovery must not capture business context")
        },
    )
}

private val OLD = BugReportDraft(title = "Saved before this page", reportId = "stable-existing-id")
private class RecoveryStore : BugReportStore, BugReportWorkspaceStore {
    var persisted = OLD
    var writes = 0
    var failingPhase = ""
    var beforeHistory: suspend () -> Unit = {}
    private fun fail(phase: String) { check(failingPhase != phase) { "Store unavailable" } }
    override suspend fun readToken(): String { fail("settings"); return "" }
    override suspend fun writeToken(value: String) = Unit
    override suspend fun readShakeEnabled() = true
    override suspend fun writeShakeEnabled(value: Boolean) = Unit
    override suspend fun readHistory(): List<BugSubmissionRecord> { fail("history"); beforeHistory(); return emptyList() }
    override suspend fun writeHistory(value: List<BugSubmissionRecord>) = Unit
    override suspend fun clearCredentials() = Unit
    override suspend fun readDraft(): BugReportDraft { fail("draft"); return persisted }
    override suspend fun writeDraft(value: BugReportDraft) { writes++; persisted = value }
    override suspend fun readPending(): List<PendingBugReport> { fail("pending"); return emptyList() }
    override suspend fun writePending(value: List<PendingBugReport>) = Unit
}
